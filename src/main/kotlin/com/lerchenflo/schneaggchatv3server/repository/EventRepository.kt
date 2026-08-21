package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.events.eventmodel.Event
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import kotlin.time.Instant

interface EventRepository : MongoRepository<Event, ObjectId> {

    fun findEventsByStartDateAfter(startDate: Instant): List<Event>

}