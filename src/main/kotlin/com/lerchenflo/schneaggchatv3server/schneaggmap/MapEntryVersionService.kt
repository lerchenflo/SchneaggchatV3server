@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap

import com.fasterxml.jackson.core.type.TypeReference
import com.lerchenflo.schneaggchatv3server.repository.MapEntryRepository
import com.lerchenflo.schneaggchatv3server.repository.MapEntryVersionRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.FieldChange
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapChangeType
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntry
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntryVersion
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.util.Json
import org.bson.types.ObjectId
import org.springframework.data.domain.PageRequest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** One row of the admin "map change log" view - a [MapEntryVersion] with values decoded to display strings. */
data class MapChangeLogEntry(
    val id: String,
    val entryId: String,
    val entryName: String?, // null if the entry itself was hard-deleted from the DB (soft-deletes keep this resolvable)
    val editedBy: String,
    val editedByUsername: String,
    val editedAt: Long,
    val changeType: MapChangeType,
    val changes: List<FieldChangeDisplay>,
)

data class FieldChangeDisplay(
    val field: String,
    val oldValue: String?,
    val newValue: String?,
)

data class MapChangeLogPage(
    val entries: List<MapChangeLogEntry>,
    val moreEntries: Boolean,
)

data class MapChangeLogEditor(
    val userId: String,
    val username: String,
)

/**
 * Persists the change-history of map entries. Writes one [MapEntryVersion] per edit, capturing only
 * the fields that changed, and (for the admin panel) reads it back as a paginated, human-readable
 * change log.
 */
@Service
class MapEntryVersionService(
    private val mapEntryVersionRepository: MapEntryVersionRepository,
    private val mapEntryRepository: MapEntryRepository,
    private val userLookupService: UserLookupService,
    private val mongoTemplate: MongoTemplate,
) {

    /** Records the creation of an entry, capturing every field as an addition (oldValue = null). */
    fun recordCreate(entry: MapEntry, requesterId: ObjectId) {
        val changes = listOf(
            FieldChange("name", null, json(entry.name)),
            FieldChange("description", null, json(entry.description)),
            FieldChange("coordinates", null, json(entry.coordinates)),
            FieldChange("locationData", null, json(entry.locationData)),
        )
        save(entry.id, requesterId, MapChangeType.CREATE, changes)
    }

    /** Records an edit, storing only the fields that differ. No-op if nothing actually changed. */
    fun recordUpdate(old: MapEntry, new: MapEntry, requesterId: ObjectId) {
        val changes = diff(old, new)
        if (changes.isEmpty()) return
        save(new.id, requesterId, MapChangeType.UPDATE, changes)
    }

    /** Records a (soft) delete as an event marker with no field changes. */
    fun recordDelete(entry: MapEntry, requesterId: ObjectId) {
        save(entry.id, requesterId, MapChangeType.DELETE, emptyList())
    }

    private fun diff(old: MapEntry, new: MapEntry): List<FieldChange> {
        val changes = mutableListOf<FieldChange>()
        if (old.name != new.name) changes += FieldChange("name", json(old.name), json(new.name))
        if (old.description != new.description) changes += FieldChange("description", json(old.description), json(new.description))
        if (old.coordinates != new.coordinates) changes += FieldChange("coordinates", json(old.coordinates), json(new.coordinates))
        if (old.locationData != new.locationData) changes += FieldChange("locationData", json(old.locationData), json(new.locationData))
        return changes
    }

    private fun save(entryId: ObjectId, requesterId: ObjectId, changeType: MapChangeType, changes: List<FieldChange>) {
        mapEntryVersionRepository.save(
            MapEntryVersion(
                entryId = entryId,
                editedBy = requesterId,
                editedByUsername = userLookupService.getUsername(requesterId),
                editedAt = Clock.System.now(),
                changeType = changeType,
                changes = changes,
            )
        )
    }

    private fun json(value: Any?): String = Json.mapper.writeValueAsString(value)

    // ─── Admin read path ────────────────────────────────────────────────────────

    /** Newest-first, optionally filtered to one editor. Powers the admin "map change log" tab. */
    fun getChangeLog(editedBy: ObjectId?, page: Int, pageSize: Int): MapChangeLogPage {
        val pageable = PageRequest.of(page, pageSize)
        val result = if (editedBy != null) {
            mapEntryVersionRepository.findByEditedByOrderByEditedAtDesc(editedBy, pageable)
        } else {
            mapEntryVersionRepository.findAllByOrderByEditedAtDesc(pageable)
        }

        // One batched lookup for the whole page instead of one per row.
        val entryNames = mapEntryRepository
            .findAllById(result.content.map { it.entryId }.distinct())
            .associate { it.id to it.name }

        val entries = result.content.map { version ->
            MapChangeLogEntry(
                id = version.id.toHexString(),
                entryId = version.entryId.toHexString(),
                entryName = entryNames[version.entryId],
                editedBy = version.editedBy.toHexString(),
                editedByUsername = version.editedByUsername,
                editedAt = version.editedAt.toEpochMilliseconds(),
                changeType = version.changeType,
                changes = version.changes.map {
                    FieldChangeDisplay(
                        field = it.field,
                        oldValue = displayValue(it.field, it.oldValue),
                        newValue = displayValue(it.field, it.newValue),
                    )
                },
            )
        }

        return MapChangeLogPage(entries = entries, moreEntries = result.hasNext())
    }

    /** Distinct editors seen in the change log, for the admin panel's filter dropdown. */
    fun getEditors(): List<MapChangeLogEditor> {
        return mongoTemplate.findDistinct(
            Query(),
            "editedBy",
            MapEntryVersion::class.java,
            ObjectId::class.java,
        ).map { userId ->
            MapChangeLogEditor(
                userId = userId.toHexString(),
                username = userLookupService.getUsername(userId),
            )
        }
    }

    /**
     * Decodes a [FieldChange]'s JSON-encoded value into something readable in a table cell.
     * Falls back to the raw JSON on any decode failure rather than silently hiding the change.
     */
    private fun displayValue(field: String, rawJson: String?): String? {
        if (rawJson == null) return null
        return try {
            when (field) {
                "name", "description" -> Json.mapper.readValue(rawJson, String::class.java)
                "coordinates" -> {
                    val latLong = Json.mapper.readValue(rawJson, LatLong::class.java)
                    "${latLong.lat}, ${latLong.long}"
                }
                "locationData" -> {
                    val attributes = Json.mapper.readValue(rawJson, object : TypeReference<List<LocationData>>() {})
                    if (attributes.isEmpty()) {
                        "keine Attribute"
                    } else {
                        attributes.joinToString { it::class.simpleName ?: "?" }
                    }
                }
                else -> rawJson
            }
        } catch (e: Exception) {
            rawJson
        }
    }
}
