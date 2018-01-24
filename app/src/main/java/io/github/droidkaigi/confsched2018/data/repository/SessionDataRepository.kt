package io.github.droidkaigi.confsched2018.data.repository

import io.github.droidkaigi.confsched2018.data.api.DroidKaigiApi
import io.github.droidkaigi.confsched2018.data.api.SessionFeedbackApi
import io.github.droidkaigi.confsched2018.data.db.FavoriteDatabase
import io.github.droidkaigi.confsched2018.data.db.SessionDatabase
import io.github.droidkaigi.confsched2018.data.db.entity.mapper.toRooms
import io.github.droidkaigi.confsched2018.data.db.entity.mapper.toSession
import io.github.droidkaigi.confsched2018.data.db.entity.mapper.toSessionFeedback
import io.github.droidkaigi.confsched2018.data.db.entity.mapper.toSpeaker
import io.github.droidkaigi.confsched2018.data.db.entity.mapper.toTopics
import io.github.droidkaigi.confsched2018.data.db.fixeddata.SpecialSessions
import io.github.droidkaigi.confsched2018.model.Level
import io.github.droidkaigi.confsched2018.model.Room
import io.github.droidkaigi.confsched2018.model.SearchResult
import io.github.droidkaigi.confsched2018.model.Session
import io.github.droidkaigi.confsched2018.model.SessionFeedback
import io.github.droidkaigi.confsched2018.model.Speaker
import io.github.droidkaigi.confsched2018.model.Topic
import io.github.droidkaigi.confsched2018.util.rx.SchedulerProvider
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.rxkotlin.Flowables
import io.reactivex.rxkotlin.Singles
import retrofit2.Response
import timber.log.Timber
import javax.inject.Inject

class SessionDataRepository @Inject constructor(
        private val api: DroidKaigiApi,
        private val sessionFeedbackApi: SessionFeedbackApi,
        private val sessionDatabase: SessionDatabase,
        private val favoriteDatabase: FavoriteDatabase,
        private val schedulerProvider: SchedulerProvider
) : SessionRepository {

    override val rooms: Flowable<List<Room>> =
            sessionDatabase.getAllRoom().toRooms()
                    .filter { it.isNotEmpty() }
    override val topics: Flowable<List<Topic>> =
            sessionDatabase.getAllTopic().toTopics()
    override val sessions: Flowable<List<Session>> =
            Flowables.combineLatest(
                    sessionDatabase.getAllSessions()
                            .filter { it.isNotEmpty() }
                            .doOnNext { if (DEBUG) Timber.d("getAllSessions") },
                    sessionDatabase.getAllSpeaker()
                            .filter { it.isNotEmpty() }
                            .doOnNext { if (DEBUG) Timber.d("getAllSpeaker") },
                    Flowable.concat(
                            Flowable.just(listOf()),
                            favoriteDatabase.favorites.onErrorReturn { listOf() }
                    )
                            .doOnNext { if (DEBUG) Timber.d("favorites") },
                    { sessionEntities, speakerEntities, favList ->
                        val firstDay = sessionEntities.first().session!!.stime.toLocalDate()
                        val speakerSessions = sessionEntities
                                .map { it.toSession(speakerEntities, favList, firstDay) }
                                .sortedWith(compareBy(
                                        { it.startTime.getTime() },
                                        { it.room.id }
                                ))

                        speakerSessions + specialSessions
                    })
                    .subscribeOn(schedulerProvider.computation())
                    .doOnNext {
                        if (DEBUG) Timber.d("size:${it.size} current:${System.currentTimeMillis()}")
                    }

    private val specialSessions: List<Session.SpecialSession> by lazy {
        SpecialSessions.getSessions()
    }

    override val sessionFeedbacks: Flowable<List<SessionFeedback>> =
            Flowables.zip(
                    sessionDatabase.getAllSessions()
                            .filter { it.isNotEmpty() }
                            .doOnNext { if (DEBUG) Timber.d("getAllSessions") },
                    sessionDatabase.getAllSessionFeedback()
                            .map { if (it.isNotEmpty()) it else emptyList() }
                            .doOnNext { if (DEBUG) Timber.d("getAllSessionFeedback") },
                    { sessionEntities, sessionFeedbackEntities ->
                        sessionFeedbackEntities.map { sessionFeedback ->
                            sessionFeedback.toSessionFeedback().apply {
                                sessionEntities.forEach { sessionWithSpeaker ->
                                    if (sessionId == sessionWithSpeaker.session!!.id) {
                                        sessionTitle = sessionWithSpeaker.session!!.title
                                    }
                                }
                            }
                        }
                    }
            )


    override val speakers: Flowable<List<Speaker>> =
            sessionDatabase.getAllSpeaker()
                    .map {
                        it.map { speaker -> speaker.toSpeaker() }
                    }

    override val roomSessions: Flowable<Map<Room, List<Session>>> =
            sessions.map { sessionList ->
                sessionList
                        .filter {
                            if (it is Session.SpecialSession) {
                                it.room != null
                            } else {
                                true
                            }
                        }
                        .groupBy {
                            when (it) {
                                is Session.SpeechSession -> it.room
                                is Session.SpecialSession -> it.room!!
                            }
                        }
            }

    override val topicSessions: Flowable<Map<Topic, List<Session.SpeechSession>>> =
            sessions.map { sessionList ->
                sessionList
                        .filterIsInstance<Session.SpeechSession>()
                        .groupBy { it.topic }
            }

    override val speakerSessions: Flowable<Map<Speaker, List<Session.SpeechSession>>> =
            sessions.map { sessionList ->
                sessionList
                        .filterIsInstance<Session.SpeechSession>()
                        .flatMap { session ->
                            session.speakers.map {
                                it to session
                            }
                        }
                        .groupBy({ it.first }, { it.second })
            }

    override val levelSessions: Flowable<Map<Level, List<Session.SpeechSession>>> =
            sessions.map { sessionList ->
                sessionList
                        .filterIsInstance<Session.SpeechSession>()
                        .groupBy { it.level }
            }

    override fun favorite(session: Session.SpeechSession): Single<Boolean> =
            favoriteDatabase.favorite(session)

    override fun refreshSessions(): Completable {
        return api.getSessions()
                .doOnSuccess { response ->
                    sessionDatabase.save(response)
                }
                .subscribeOn(schedulerProvider.computation())
                .toCompletable()
    }

    override fun search(query: String): Single<SearchResult> = Singles.zip(
            sessions.map {
                it
                        .filterIsInstance<Session.SpeechSession>()
                        .filter { it.title.contains(query, true) || it.desc.contains(query, true) }
            }.firstOrError(),
            speakers.map {
                it.filter { it.name.contains(query, true) || it.tagLine.contains(query, true) }
            }.firstOrError(),
            { sessions: List<Session>, speakers: List<Speaker> ->
                SearchResult(sessions, speakers)
            })

    override fun saveSessionFeedback(sessionFeedback: SessionFeedback): Completable =
            Completable.create { sessionDatabase.saveSessionFeedback(sessionFeedback) }
                    .subscribeOn(schedulerProvider.computation())

    override fun submitSessionFeedback(sessionFeedback: SessionFeedback): Single<Response<Void>> =
            sessionFeedbackApi.submitSessionFeedback(
                    sessionId = sessionFeedback.sessionId,
                    sessionTitle = sessionFeedback.sessionTitle,
                    totalEvaluation = sessionFeedback.totalEvaluation,
                    relevancy = sessionFeedback.relevancy,
                    asExpected = sessionFeedback.asExpected,
                    difficulty = sessionFeedback.difficulty,
                    knowledgeable = sessionFeedback.knowledgeable,
                    comment = sessionFeedback.comment
            ).subscribeOn(schedulerProvider.computation())


    companion object {
        const val DEBUG = false
    }
}
