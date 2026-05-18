@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap.model

import kotlin.time.ExperimentalTime

data class SubtypeResponse(
    val id: String,
    val mainTypeKey: String,
    val name: String,
    val createdBy: String,
    val createdAt: Long,
    val deleted: Boolean,
)

fun Subtype.toSubtypeResponse(): SubtypeResponse = SubtypeResponse(
    id = id.toHexString(),
    mainTypeKey = mainTypeKey,
    name = name,
    createdBy = createdBy.toHexString(),
    createdAt = createdAt.toEpochMilliseconds(),
    deleted = deleted,
)
