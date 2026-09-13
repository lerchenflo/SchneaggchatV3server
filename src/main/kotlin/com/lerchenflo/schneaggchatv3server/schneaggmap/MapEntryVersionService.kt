@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap

import com.fasterxml.jackson.core.type.TypeReference
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.repository.MapEntryRepository
import com.lerchenflo.schneaggchatv3server.repository.MapEntryVersionRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.FieldChange
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapChangeType
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntry
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntryVersion
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.util.Json
import com.lerchenflo.schneaggchatv3server.util.withOptimisticRetry
import org.bson.types.ObjectId
import org.springframework.data.domain.PageRequest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

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
    val revertOfVersionId: String?,
    val revertNote: String?,
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
    private val notificationService: NotificationService,
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

    // ─── Revert / undo ──────────────────────────────────────────────────────────

    /**
     * Undoes exactly one change from the change log, regardless of what happened to the entry
     * since. A CREATE is undone by (soft) deleting the entry, a DELETE by restoring it, and an
     * UPDATE by writing back the old value of each field it changed - other fields, and any edits
     * made by someone else afterwards, are left alone. The revert itself is written as a new,
     * clearly-marked version, so it shows up in the log like any other change.
     */
    fun revertVersion(versionId: ObjectId, requesterId: ObjectId) {
        val version = mapEntryVersionRepository.findById(versionId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Change not found")
        }

        withOptimisticRetry {
            val existing = mapEntryRepository.findById(version.entryId).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Map entry not found")
            }

            var name = existing.name
            var description = existing.description
            var coordinates = existing.coordinates
            var locationData = existing.locationData
            var deleted = existing.deleted

            when (version.changeType) {
                MapChangeType.CREATE -> deleted = true
                MapChangeType.DELETE -> deleted = false
                MapChangeType.RESTORE -> deleted = true
                MapChangeType.UPDATE -> for (change in version.changes) {
                    when (change.field) {
                        "name" -> name = decode(change.oldValue, String::class.java) ?: name
                        "description" -> description = decode(change.oldValue, String::class.java) ?: description
                        "coordinates" -> coordinates = decode(change.oldValue, LatLong::class.java) ?: coordinates
                        "locationData" -> locationData = decodeLocationData(change.oldValue) ?: locationData
                    }
                }
            }

            applyEntryState(
                existing = existing,
                name = name,
                description = description,
                coordinates = coordinates,
                locationData = locationData,
                deleted = deleted,
                requesterId = requesterId,
                revertOfVersionId = version.id,
                revertNote = null,
            )
        }
    }

    /**
     * Undoes every change [targetUserId] made to the map between [from] and [to] (inclusive) - for
     * when they messed something up. For every entry they touched in that window, the entry is
     * overwritten with the state it had right before their earliest edit in the window, discarding
     * everything that happened to it from that point on (including edits by other users, and
     * including deletions - a delete in the window is undone just like any other change). Returns
     * the number of entries that were changed.
     */
    fun revertUserChanges(targetUserId: ObjectId, from: Instant, to: Instant, requesterId: ObjectId): Int {
        val userVersionsInRange = mapEntryVersionRepository.findByEditedBy(targetUserId)
            .filter { it.editedAt >= from && it.editedAt <= to }

        val earliestByEntry = userVersionsInRange
            .groupBy { it.entryId }
            .mapValues { (_, versions) -> versions.minBy { it.editedAt } }

        val username = userLookupService.getUsername(targetUserId)
        val note = "Bulk revert: changes by $username between ${from.toEpochMilliseconds()} and ${to.toEpochMilliseconds()}"

        var revertedCount = 0
        for ((entryId, earliestTargetVersion) in earliestByEntry) {
            val reverted = revertEntryTo(entryId, earliestTargetVersion.editedAt, requesterId, note)
            if (reverted) revertedCount++
        }
        return revertedCount
    }

    /**
     * Rolls the whole map back to how it looked at [at] - every entry touched after that instant
     * (by anyone) is overwritten with the state it had at that instant, deletions included. Entries
     * untouched since [at] are left alone. Returns the number of entries that were changed.
     */
    fun restoreMapToTimestamp(at: Instant, requesterId: ObjectId): Int {
        val affectedEntryIds = mongoTemplate.findDistinct(
            Query(Criteria.where("editedAt").gt(at)),
            "entryId",
            MapEntryVersion::class.java,
            ObjectId::class.java,
        )

        val note = "Restore to ${at.toEpochMilliseconds()}"

        var revertedCount = 0
        for (entryId in affectedEntryIds) {
            val reverted = revertEntryTo(entryId, at, requesterId, note)
            if (reverted) revertedCount++
        }
        return revertedCount
    }

    /**
     * Reconstructs [entryId]'s state as it was strictly before [at] by replaying its versions
     * newest-first and undoing every one with `editedAt >= at`, then overwrites the current entry
     * with that state. Shared by [revertUserChanges] (per-entry cutoff = that user's earliest edit
     * in the window) and [restoreMapToTimestamp] (same cutoff for every affected entry).
     */
    private fun revertEntryTo(entryId: ObjectId, at: Instant, requesterId: ObjectId, note: String): Boolean {
        return withOptimisticRetry {
            val existing = mapEntryRepository.findById(entryId).orElse(null) ?: return@withOptimisticRetry false
            val toUndo = mapEntryVersionRepository.findByEntryIdOrderByEditedAtDesc(entryId)
                .filter { it.editedAt >= at }
            if (toUndo.isEmpty()) return@withOptimisticRetry false

            var name = existing.name
            var description = existing.description
            var coordinates = existing.coordinates
            var locationData = existing.locationData
            var deleted = existing.deleted

            for (version in toUndo) {
                when (version.changeType) {
                    MapChangeType.CREATE -> deleted = true
                    MapChangeType.DELETE -> deleted = false
                    MapChangeType.RESTORE -> deleted = true
                    MapChangeType.UPDATE -> for (change in version.changes) {
                        when (change.field) {
                            "name" -> name = decode(change.oldValue, String::class.java) ?: name
                            "description" -> description = decode(change.oldValue, String::class.java) ?: description
                            "coordinates" -> coordinates = decode(change.oldValue, LatLong::class.java) ?: coordinates
                            "locationData" -> locationData = decodeLocationData(change.oldValue) ?: locationData
                        }
                    }
                }
            }

            applyEntryState(
                existing = existing,
                name = name,
                description = description,
                coordinates = coordinates,
                locationData = locationData,
                deleted = deleted,
                requesterId = requesterId,
                revertOfVersionId = null,
                revertNote = note,
            )
        }
    }

    /**
     * Writes the reconstructed field/deleted state onto the entry (no-op if nothing actually
     * changed), notifies clients the same way a normal edit/delete would, and records the result as
     * a new version so the revert itself is auditable. Returns whether anything was written.
     */
    private fun applyEntryState(
        existing: MapEntry,
        name: String,
        description: String,
        coordinates: LatLong,
        locationData: List<LocationData>,
        deleted: Boolean,
        requesterId: ObjectId,
        revertOfVersionId: ObjectId?,
        revertNote: String?,
    ): Boolean {
        val unchanged = existing.name == name &&
            existing.description == description &&
            existing.coordinates == coordinates &&
            existing.locationData == locationData &&
            existing.deleted == deleted
        if (unchanged) return false

        val now = Clock.System.now()
        val updated = existing.copy(
            name = name,
            description = description,
            coordinates = coordinates,
            locationData = locationData,
            deleted = deleted,
            updatedBy = requesterId,
            updatedAt = now,
        )
        val saved = mapEntryRepository.save(updated)

        val changeType = when {
            !existing.deleted && deleted -> MapChangeType.DELETE
            existing.deleted && !deleted -> MapChangeType.RESTORE
            else -> MapChangeType.UPDATE
        }
        val changes = if (changeType == MapChangeType.UPDATE) diff(existing, saved) else emptyList()

        mapEntryVersionRepository.save(
            MapEntryVersion(
                entryId = saved.id,
                editedBy = requesterId,
                editedByUsername = userLookupService.getUsername(requesterId),
                editedAt = now,
                changeType = changeType,
                changes = changes,
                revertOfVersionId = revertOfVersionId,
                revertNote = revertNote,
            )
        )

        notificationService.notifyMapUpdate(
            saved,
            newEntry = existing.deleted && !deleted,
            deleted = deleted,
            excludeUserId = null,
        )
        return true
    }

    private fun <T> decode(rawJson: String?, type: Class<T>): T? {
        if (rawJson == null) return null
        return Json.mapper.readValue(rawJson, type)
    }

    private fun decodeLocationData(rawJson: String?): List<LocationData>? {
        if (rawJson == null) return null
        return Json.mapper.readValue(rawJson, object : TypeReference<List<LocationData>>() {})
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
                revertOfVersionId = version.revertOfVersionId?.toHexString(),
                revertNote = version.revertNote,
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
