@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap

import com.fasterxml.jackson.core.type.TypeReference
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.repository.MapEntryRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.*
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.Json
import org.bson.types.ObjectId
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

data class MapSyncResponse(
    val updatedEntries: List<MapEntryResponse>,
    val deletedEntries: List<String>,
    val moreEntries: Boolean,
)


@Service
class SchneaggmapService(
    private val mapEntryRepository: MapEntryRepository,
    private val notificationService: NotificationService,
) {

    // ─── Validation ──────────────────────────────────────────────────────────

    fun validate(data: LocationData) {
        val errors = mutableListOf<String>()

        for (def in data.schema()) {
            if (!def.required) continue

            val value = resolveValue(data, def.key)
            if (value == null) {
                errors += "${def.key}: required field is missing"
                continue
            }

            when (def) {
                is AttributeDefinition.IntDef -> {
                    val v = (value as? AttributeValue.IntValue)?.value
                        ?: run { errors += "${def.key}: expected int"; continue }
                    def.min?.let { if (v < it) errors += "${def.key}: $v is below minimum $it" }
                    def.max?.let { if (v > it) errors += "${def.key}: $v exceeds maximum $it" }
                }
                is AttributeDefinition.DoubleDef -> {
                    val v = (value as? AttributeValue.DoubleValue)?.value
                        ?: run { errors += "${def.key}: expected double"; continue }
                    def.min?.let { if (v < it) errors += "${def.key}: $v is below minimum $it" }
                    def.max?.let { if (v > it) errors += "${def.key}: $v exceeds maximum $it" }
                }
                is AttributeDefinition.StringDef -> {
                    val v = (value as? AttributeValue.StringValue)?.value
                        ?: run { errors += "${def.key}: expected string"; continue }
                    def.maxLength?.let { if (v.length > it) errors += "${def.key}: length ${v.length} exceeds maximum $it" }
                }
                is AttributeDefinition.BoolDef -> {
                    if (value !is AttributeValue.BoolValue)
                        errors += "${def.key}: expected bool"
                }
            }
        }

        if (errors.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid ${data::class.simpleName}: ${errors.joinToString("; ")}"
            )
        }
    }

    // Resolves a field by key using reflection — keeps the validator generic
    private fun resolveValue(data: LocationData, key: String): AttributeValue? {
        return data::class.members
            .firstOrNull { it.name == key }
            ?.call(data) as? AttributeValue
    }


    // ─── CRUD ─────────────────────────────────────────────────────────────────

    fun createMapEntry(
        name: String,
        description: String,
        coordinates: LatLong,
        locationData: LocationData,
        requesterId: ObjectId,
    ): MapEntry {
        validate(locationData)

        val now = Clock.System.now()
        val entry = mapEntryRepository.save(
            MapEntry(
                name         = name,
                description  = description,
                coordinates  = coordinates,
                locationData = locationData,
                createdBy    = requesterId,
                createdAt    = now,
                updatedBy    = requesterId,
                updatedAt    = now,
            )
        )
        notificationService.notifyMapUpdate(entry, newEntry = true, deleted = false, changingUserId = requesterId)
        return entry
    }

    fun editMapEntry(
        entryId: ObjectId,
        name: String,
        description: String,
        coordinates: LatLong,
        locationData: LocationData,
        requesterId: ObjectId,
    ): MapEntry {
        val existing = mapEntryRepository.findById(entryId).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Map entry not found")

        validate(locationData)

        val updated = existing.copy(
            name         = name,
            description  = description,
            coordinates  = coordinates,
            locationData = locationData,
            updatedBy    = requesterId,
            updatedAt    = Clock.System.now(),
        )
        val saved = mapEntryRepository.save(updated)
        notificationService.notifyMapUpdate(saved, newEntry = false, deleted = false, changingUserId = requesterId)
        return saved
    }

    fun deleteMapEntry(entryId: ObjectId, requesterId: ObjectId) {
        val existing = mapEntryRepository.findById(entryId).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Map entry not found")
        val deleted = existing.copy(
            deleted = true,
            updatedBy = requesterId,
            updatedAt = Clock.System.now(),
        )
        val saved = mapEntryRepository.save(deleted)
        notificationService.notifyMapUpdate(saved, newEntry = false, deleted = true, changingUserId = requesterId)
    }

    fun mapSync(
        clientEntries: List<UserService.IdTimeStamp>,
        page: Int,
        pageSize: Int,
    ): MapSyncResponse {
        val clientMap = clientEntries.associate { it.id to it.timeStamp }
        val allEntries = mapEntryRepository.findByDeletedFalse()
        val serverIds = allEntries.map { it.id.toHexString() }.toSet()

        val toAdd = allEntries.filter { it.id.toHexString() !in clientMap }
        val toUpdate = allEntries.filter { entry ->
            clientMap[entry.id.toHexString()]?.toLongOrNull()?.let { clientTs ->
                entry.updatedAt.toEpochMilliseconds() > clientTs
            } ?: false
        }

        val allUpdated = (toAdd + toUpdate)
            .sortedByDescending { it.updatedAt.toEpochMilliseconds() }

        val start = page * pageSize
        val paged = allUpdated.drop(start).take(pageSize).map { it.toMapEntryResponse() }
        val moreEntries = (start + pageSize) < allUpdated.size

        val deletedEntries = if (page == 0) clientMap.keys.filter { it !in serverIds } else emptyList()

        return MapSyncResponse(
            updatedEntries = paged,
            deletedEntries = deletedEntries,
            moreEntries = moreEntries,
        )
    }


    // ─── Boot hooks ───────────────────────────────────────────────────────────

    fun importLegacyMapEntries(creatorId: ObjectId) {
        if (mapEntryRepository.count() > 0) {
            AppLogger.info("Legacy map entry import skipped: collection not empty")
            return
        }

        val resource = ClassPathResource("seed/v2schneaggmaplocations.json")
        val raw = Json.mapper.readValue(
            resource.inputStream,
            object : TypeReference<List<Map<String, Any>>>() {}
        )

        val skippedCategories = setOf("Kraftraum", "Schaffa", "Schule")
        val speedRegex = Regex("""^(\d+)""")
        val batch = mutableListOf<MapEntry>()
        var skipped = 0

        for (entry in raw) {
            val categoryName = entry["Name"] as? String ?: continue
            if (categoryName in skippedCategories) { skipped++; continue }

            val beschreibung = (entry["Beschreibung"] as? String).orEmpty()
            val createdAt  = Instant.fromEpochMilliseconds((entry["CreationTime"] as? String)?.toLongOrNull() ?: 0L)
            val updatedAt  = Instant.fromEpochMilliseconds((entry["LastChanged"]   as? String)?.toLongOrNull() ?: 0L)
            val lat = (entry["Latitude"]  as? String)?.toDoubleOrNull() ?: continue
            val lng = (entry["Longitude"] as? String)?.toDoubleOrNull() ?: continue

            val locationData: LocationData
            val name: String
            val description: String

            when (categoryName) {
                "Radar" -> {
                    val speed = speedRegex.find(beschreibung)?.groupValues?.get(1)?.toIntOrNull()
                    locationData = LocationData.Radar(
                        speedLimit = AttributeValue.IntValue(speed ?: 0),
                        radarType  = LocationData.RadarType.SPEED,
                    )
                    name        = if (speed != null) "$speed km/h Radar" else "Radar"
                    description = if (speed != null) "" else "needs to be filled in"
                }

                "Polizei" -> {
                    locationData = LocationData.Radar(
                        speedLimit = AttributeValue.IntValue(0),
                        radarType  = LocationData.RadarType.POLICE,
                    )
                    name        = "Polizeikontrolle"
                    description = beschreibung
                }

                "Motorradstrecke" -> {
                    locationData = LocationData.Street(
                        mautFee         = null,
                        heightLimit     = null,
                        closedInWinter  = null,
                        wheeliesAllowed = null,
                    )
                    name        = beschreibung.ifBlank { "Motorradstrecke" }
                    description = ""
                }

                "Wheeliespot" -> {
                    locationData = LocationData.Street(
                        mautFee         = null,
                        heightLimit     = null,
                        closedInWinter  = null,
                        wheeliesAllowed = AttributeValue.BoolValue(true),
                    )
                    name        = beschreibung.ifBlank { "Wheeliespot" }
                    description = ""
                }

                "Sehenswuerdigkeit" -> {
                    locationData = LocationData.SightSeeing(entryFee = null)
                    name        = beschreibung.ifBlank { "Sehenswürdigkeit" }
                    description = ""
                }

                "Badespot" -> {
                    locationData = LocationData.SwimmingLocation(indoor = null)
                    name        = beschreibung.ifBlank { "Badespot" }
                    description = ""
                }

                "Partylocation" -> {
                    locationData = LocationData.PartyLocation(entryFee = null)
                    name        = beschreibung.ifBlank { "Partylocation" }
                    description = ""
                }

                "Kebab" -> {
                    locationData = LocationData.Food(
                        foodType     = LocationData.FoodType.KEBAB,
                        allYouCanEat = null,
                    )
                    name        = beschreibung.ifBlank { "Kebab" }
                    description = ""
                }

                "Essen" -> {
                    locationData = LocationData.Food(
                        foodType     = LocationData.FoodType.OTHER,
                        allYouCanEat = null,
                    )
                    name        = beschreibung.ifBlank { "Essen" }
                    description = ""
                }

                else -> { skipped++; continue }
            }

            batch.add(
                MapEntry(
                    name = name,
                    description = description,
                    coordinates = LatLong(lat = lat, long = lng),
                    locationData = locationData,
                    createdBy = creatorId,
                    createdAt = createdAt,
                    updatedBy = creatorId,
                    updatedAt = updatedAt,
                    deleted = false,
                )
            )
        }

        mapEntryRepository.saveAll(batch)
        AppLogger.success("Legacy map entry import complete: imported=${batch.size}, skipped=$skipped")
    }
}
