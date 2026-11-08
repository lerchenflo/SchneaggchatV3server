@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.events

import com.lerchenflo.schneaggchatv3server.events.eventmodel.Event
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventParticipation
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventParticipationStatus
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventResponse
import com.lerchenflo.schneaggchatv3server.events.eventmodel.SentReminder
import com.lerchenflo.schneaggchatv3server.events.eventmodel.hasEnded
import com.lerchenflo.schneaggchatv3server.events.eventmodel.referencedUserIds
import com.lerchenflo.schneaggchatv3server.events.eventmodel.toResponse
import com.lerchenflo.schneaggchatv3server.repository.EventRepository
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
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
import kotlin.time.Instant

@Service
class EventsLookupService(
    private val eventRepository: EventRepository,
    private val mongoTemplate: MongoTemplate,
    private val userLookupService: UserLookupService,
) {
    /** The response of [event], with the names of every user it references. */
    fun toResponse(event: Event): EventResponse = toResponses(listOf(event)).first()

    /** Like [toResponse], with one user lookup for all [events]. */
    fun toResponses(events: List<Event>): List<EventResponse> {
        if (events.isEmpty()) return emptyList()
        val names = userLookupService
            .findAllById(events.flatMap { it.referencedUserIds() }.distinct())
            .associate { it.id to it.username }
        return events.map { it.toResponse(names) }
    }

    /**
     * Events that have not started yet and have at least one reminder. The scheduler picks the due
     * reminders from these in memory: there are few future events, and the due time depends on
     * each event's own lead times.
     */
    fun getUpcomingEventsWithReminders(now: Instant): List<Event> {
        val query = Query(
            Criteria.where("reminderMinutes.0").exists(true)
                .and("startDate.epochSeconds").gt(now.epochSeconds)
        )
        return mongoTemplate.find(query, Event::class.java)
    }

    /**
     * Marks the reminders [leadMinutes] of [event] as handled for its current start, in one atomic
     * write. Only succeeds while the start is still the one [event] was read with and
     * [claimedLead] is not handled yet, so two scheduler runs (or a run and an edit that moved the
     * start) can never send the same reminder twice.
     *
     * @return true when this caller claimed the reminder and must send it.
     */
    fun claimReminders(event: Event, claimedLead: Int, leadMinutes: Collection<Int>): Boolean {
        val entries = leadMinutes.distinct().map { SentReminder(leadMinutes = it, startDate = event.startDate) }
        val query = Query(
            Criteria.where("_id").`is`(event.id)
                .and("startDate.epochSeconds").`is`(event.startDate.epochSeconds)
                .and("startDate.nanosecondsOfSecond").`is`(event.startDate.nanosecondsOfSecond)
                .and("sentReminders").not().elemMatch(
                    Criteria.where("leadMinutes").`is`(claimedLead)
                        .and("startDate.epochSeconds").`is`(event.startDate.epochSeconds)
                        .and("startDate.nanosecondsOfSecond").`is`(event.startDate.nanosecondsOfSecond)
                )
        )
        val update = Update().addToSet("sentReminders").each(*entries.toTypedArray())
        return mongoTemplate.updateFirst(query, update, Event::class.java).modifiedCount > 0
    }

    fun getAllEvents(): List<Event> {
        return eventRepository.findAll()
    }

    /**
     * Every event that has not ended yet - see [hasEnded]: still before its close date, or, when it
     * has none, before its start. Filtering on the start date alone would report a running event
     * as deleted to every client the moment it starts (worst for multi-day events), while its
     * group chat lives on until the close date plus the creator's delete delay.
     */
    fun getActiveEvents(): List<Event> {
        val now = Clock.System.now()
        val query = Query(
            Criteria().orOperator(
                Criteria.where("closeDate").gt(now),
                Criteria.where("closeDate").`is`(null).and("startDate").gt(now),
            )
        )
        return mongoTemplate.find(query, Event::class.java)
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
     * Rewrites [userId]'s existing entry without ever creating one, so a user who never responded
     * stays unseen. Used to take an accept back when someone leaves or is removed from an event's
     * group, since being in that group is what counted as the accept.
     *
     * @return the updated event, or null when the user had no entry to change.
     */
    fun setParticipationIfPresent(eventId: ObjectId, userId: ObjectId, status: EventParticipationStatus): Event? {
        val now = Clock.System.now()
        return mongoTemplate.findAndModify(
            Query(Criteria.where("_id").`is`(eventId).and("participations.userId").`is`(userId)),
            Update()
                .set("participations.\$.status", status)
                .set("participations.\$.updatedAt", now)
                .set("updatedAt", now),
            FindAndModifyOptions.options().returnNew(true),
            Event::class.java
        )
    }

    /**
     * Adds [userId] to the event's `invitedUsers`, so someone who was put into an
     * INVITED_FRIENDS_ONLY event's group by an admin can actually access the event they now count
     * as attending. Uses addToSet under a filter that requires the user to still be absent, so
     * concurrent calls can never duplicate an entry.
     *
     * @return the updated event, or null when the user was already invited.
     */
    fun addInvitedUser(eventId: ObjectId, userId: ObjectId): Event? {
        val now = Clock.System.now()
        return mongoTemplate.findAndModify(
            Query(Criteria.where("_id").`is`(eventId).and("invitedUsers").ne(userId)),
            Update()
                .addToSet("invitedUsers", userId)
                .set("updatedAt", now),
            FindAndModifyOptions.options().returnNew(true),
            Event::class.java
        )
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
