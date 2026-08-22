package com.lerchenflo.schneaggchatv3server.events

import com.lerchenflo.schneaggchatv3server.events.eventmodel.Event
import com.lerchenflo.schneaggchatv3server.repository.EventRepository
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Clock

@Service
class EventsLookupService(
    private val eventRepository: EventRepository,
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

}