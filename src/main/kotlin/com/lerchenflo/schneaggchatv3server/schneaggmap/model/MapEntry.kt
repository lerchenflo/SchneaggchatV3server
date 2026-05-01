@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Document("map_entries")
data class MapEntry(
    @Id val id: ObjectId = ObjectId(),
    @Indexed val mainTypeKey: String,
    val subtypeIds: List<ObjectId>,
    val coordinates: LatLong,
    val description: String,
    val attributes: Map<String, AttributeValue>,
    val createdBy: ObjectId,
    val createdAt: Instant,
    val lastChangedBy: ObjectId,
    val lastChangedAt: Instant,
    val deleted: Boolean = false,
)
