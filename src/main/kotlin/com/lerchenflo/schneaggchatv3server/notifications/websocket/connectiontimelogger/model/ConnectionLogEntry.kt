package com.lerchenflo.schneaggchatv3server.notifications.websocket.connectiontimelogger.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.Duration
import kotlin.time.Instant

@TypeAlias("connectionentry")
@Document("socketconnections")
data class ConnectionLogEntry(
    @Id val id: ObjectId = ObjectId.get(),

    @Indexed
    val userId: ObjectId,
    val startTime: Instant,
    val endTime: Instant,
    val duration: Duration
)
