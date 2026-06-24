@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap

import com.lerchenflo.schneaggchatv3server.repository.MapEntryVersionRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.FieldChange
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapChangeType
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntry
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntryVersion
import com.lerchenflo.schneaggchatv3server.util.Json
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Persists the change-history of map entries. Writes one [MapEntryVersion] per edit, capturing only
 * the fields that changed. Reporting/read-back is not wired yet — this is server-side persistence
 * only (see [MapEntryVersionRepository.findByEntryIdOrderByEditedAtDesc] for the future read path).
 */
@Service
class MapEntryVersionService(
    private val mapEntryVersionRepository: MapEntryVersionRepository,
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
                editedAt = Clock.System.now(),
                changeType = changeType,
                changes = changes,
            )
        )
    }

    private fun json(value: Any?): String = Json.mapper.writeValueAsString(value)
}
