package com.lerchenflo.schneaggchatv3server.util

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.mapping.Document

/**
 * One monotonic counter per [SyncCollection], atomically incremented by [VersionCounterService].
 * `id` is [SyncCollection.key], not the enum name, so the stored value survives an enum rename.
 */
@TypeAlias("counter")
@Document("counters")
data class VersionCounter(
    @Id val id: String,
    val seq: Long,
)
