@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Append-only change-history for a [MapEntry]. One document per edit, storing only the fields that
 * actually changed (see [FieldChange]) so the collection stays small and the synced entry is never
 * inflated by history.
 */
@Document("map_entry_versions")
@TypeAlias("mapentryversion")
@CompoundIndex(name = "entryid_editedat_idx", def = "{'entryId': 1, 'editedAt': -1}")
data class MapEntryVersion(
    @Id val id: ObjectId = ObjectId(),
    val entryId: ObjectId,
    val editedBy: ObjectId,
    val editedByUsername: String, //Resolve username for simple lookup
    val editedAt: Instant,
    val changeType: MapChangeType,
    val changes: List<FieldChange> = emptyList(),
)

enum class MapChangeType { CREATE, UPDATE, DELETE }

/**
 * A single field that changed. [oldValue]/[newValue] are JSON-encoded with the shared `Json.mapper`
 * so heterogeneous fields (plain strings, [LatLong], polymorphic `locationData`) round-trip through
 * one `String?` column without needing dedicated Mongo converters. [oldValue] is null when the field
 * is newly set (CREATE); [newValue] is null on DELETE.
 */
data class FieldChange(
    val field: String,
    val oldValue: String?,
    val newValue: String?,
)
