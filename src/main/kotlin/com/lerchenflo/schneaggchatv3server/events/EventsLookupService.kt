@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.events

import com.lerchenflo.schneaggchatv3server.events.eventmodel.Event
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventParticipation
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventParticipationStatus
import com.lerchenflo.schneaggchatv3server.repository.EventRepository
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Service
class EventsLookupService(
    private val eventRepository: EventRepository,
    private val mongoTemplate: MongoTemplate,
) {
    fun getAllEvents(): List<Event> {
        return eventRepository.findAll()
    }

    fun getFutureEvents(): List<Event> {
        return eventRepository.findEventsByStartDateAfter(Clock.System.now())
    }

    fun findById(eventId: ObjectId): Event? {
        return eventRepository.findById(eventId).getOrNull()
    }

    fun findById(eventId: String): Event? {
        return findById(ObjectId(eventId))
    }

    fun findByGroupId(groupId: ObjectId): Event? {
        return eventRepository.findByGroupId(groupId)
    }

    fun save(event: Event): Event {
        return eventRepository.save(event)
    }

    fun deleteEvent(event: Event) {
        eventRepository.delete(event)
    }

    fun findParticipation(event: Event, userId: ObjectId): EventParticipation? {
        return event.participations.firstOrNull { it.userId == userId }
    }

    /**
     * Writes [userId]'s entry in the event's `participations` array and bumps `updatedAt` so the existing
     * IdTimeStamp sync and the EventChange push carry the change. The only place that array is
     * written.
     *
     * Never a load-modify-save: [Event] has no `@Version`, so a whole-document write would let two
     * users responding at the same time overwrite each other. Instead either the user's existing
     * element is updated in place through the positional operator, or a new one is pushed under a
     * filter that requires the user to still be absent - so a duplicate entry for one user is
     * impossible even under concurrent requests.
     *
     * SEEN never downgrades an existing entry: it only ever inserts, and returns null when the user
     * already responded (no write, no push).
     *
     * @return the updated event, or null when nothing was written.
     */
    fun upsertParticipation(eventId: ObjectId, userId: ObjectId, status: EventParticipationStatus): Event? {
        val now = Clock.System.now()
        val returnNew = FindAndModifyOptions.options().returnNew(true)

        val insertQuery = Query(
            Criteria.where("_id").`is`(eventId).and("participations.userId").ne(userId)
        )
        val insert = Update()
            .push("participations", EventParticipation(userId = userId, status = status, updatedAt = now))
            .set("updatedAt", now)

        if (status == EventParticipationStatus.SEEN) {
            return mongoTemplate.findAndModify(insertQuery, insert, returnNew, Event::class.java)
        }

        val replaceQuery = Query(
            Criteria.where("_id").`is`(eventId).and("participations.userId").`is`(userId)
        )
        val replace = Update()
            .set("participations.\$.status", status)
            .set("participations.\$.updatedAt", now)
            .set("updatedAt", now)

        // The user usually already has an entry (opening the event marks SEEN first), so try the
        // in-place update before the insert. Each branch retries the other once: between the two
        // calls a concurrent request of the same user may have inserted or - via a full event
        // save in upsertEvent - dropped the element.
        return mongoTemplate.findAndModify(replaceQuery, replace, returnNew, Event::class.java)
            ?: mongoTemplate.findAndModify(insertQuery, insert, returnNew, Event::class.java)
            ?: mongoTemplate.findAndModify(replaceQuery, replace, returnNew, Event::class.java)
    }
}
