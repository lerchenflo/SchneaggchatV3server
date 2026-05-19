@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap.model

import kotlin.time.ExperimentalTime

data class MapEntryResponse(
    val id: String,
    val mainTypeKey: String,
    val subtypeIds: List<String>,
    val coordinates: LatLong,
    val description: String,
    val attributes: Map<String, AttributeValue>,
    val createdBy: String,
    val createdAt: Long,
    val lastChangedBy: String,
    val lastChangedAt: Long,
    val deleted: Boolean,
)

fun MapEntry.toMapEntryResponse(): MapEntryResponse = MapEntryResponse(
    id = id.toHexString(),
    mainTypeKey = mainTypeKey,
    subtypeIds = subtypeIds.map { it.toHexString() },
    coordinates = coordinates,
    description = description,
    attributes = attributes.mapValues { it.value.toAttributeValue() },
    createdBy = createdBy.toHexString(),
    createdAt = createdAt.toEpochMilliseconds(),
    lastChangedBy = lastChangedBy.toHexString(),
    lastChangedAt = lastChangedAt.toEpochMilliseconds(),
    deleted = deleted,
)
