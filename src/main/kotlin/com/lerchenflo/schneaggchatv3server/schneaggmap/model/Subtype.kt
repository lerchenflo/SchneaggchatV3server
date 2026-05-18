@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Document("map_subtypes")
data class Subtype(
    @Id val id: ObjectId = ObjectId(),
    @Indexed val mainTypeKey: String,
    val name: String,
    val createdBy: ObjectId,
    val createdAt: Instant,
    val deleted: Boolean = false,
)
