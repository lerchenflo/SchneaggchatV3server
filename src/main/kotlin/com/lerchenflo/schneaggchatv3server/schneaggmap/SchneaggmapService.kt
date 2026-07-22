@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap

import com.fasterxml.jackson.core.type.TypeReference
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.repository.MapEntryRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.*
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.Json
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import com.lerchenflo.schneaggchatv3server.util.withOptimisticRetry
import org.bson.types.ObjectId
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.Charset
import kotlin.collections.forEach
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
    private val userLookupService: UserLookupService,
    private val loggingService: LoggingService,
    private val mapEntryVersionService: MapEntryVersionService,
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

                is AttributeDefinition.LongDef -> {
                    val v = (value as? AttributeValue.LongValue)?.value
                        ?: run { errors += "${def.key}: expected long"; continue }
                    def.min?.let { if (v < it) errors += "${def.key}: $v is below minimum $it" }
                    def.max?.let { if (v > it) errors += "${def.key}: $v exceeds maximum $it" }
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

    fun upsertMapEntry(
        entryId: ObjectId? = null,
        name: String,
        description: String,
        coordinates: LatLong,
        locationDatas: List<LocationData>,
        requesterId: ObjectId,
    ): MapEntry {
        return withOptimisticRetry {
            val existing = entryId?.let {
                mapEntryRepository.findById(it).orElse(null)
                    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Map entry not found")
            }

            locationDatas.forEach { locationData ->
                validate(locationData)
            }

            val now = Clock.System.now()
            val entry = existing?.copy(
                name         = name,
                description  = description,
                coordinates  = coordinates,
                locationData = locationDatas,
                updatedBy    = requesterId,
                updatedAt    = now,
            ) ?: MapEntry(
                name         = name,
                description  = description,
                coordinates  = coordinates,
                locationData = locationDatas,
                createdBy    = requesterId,
                createdAt    = now,
                updatedBy    = requesterId,
                updatedAt    = now,
            )

            AppLogger.info("MAP: ENTRY \"$name\" ${if (existing != null) "updated" else "created"} by ${userLookupService.getUsername(requesterId)}")

            loggingService.log(
                userId = requesterId,
                logType = if (existing == null) LogType.MAP_ENTRY_CREATED else LogType.MAP_ENTRY_EDITED,
            )
            val saved = mapEntryRepository.save(entry)
            if (existing == null) {
                mapEntryVersionService.recordCreate(saved, requesterId)
            } else {
                mapEntryVersionService.recordUpdate(existing, saved, requesterId)
            }
            notificationService.notifyMapUpdate(saved, newEntry = existing == null, deleted = false, excludeUserId = requesterId) //Exclude creator, he gets the new entry with the response when upserting
            saved
        }
    }

    /** Builds the client facing response, resolving the last editor's username. */
    fun toResponse(entry: MapEntry): MapEntryResponse = entry.toMapEntryResponse(
        updatedByName = userLookupService.findById(entry.updatedBy)?.username ?: "Unknown" )

    fun deleteMapEntry(entryId: ObjectId, requesterId: ObjectId) {
        val existing = mapEntryRepository.findById(entryId).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Map entry not found")
        val deleted = existing.copy(
            deleted = true,
            updatedBy = requesterId,
            updatedAt = Clock.System.now(),
        )
        val saved = mapEntryRepository.save(deleted)
        mapEntryVersionService.recordDelete(saved, requesterId)
        notificationService.notifyMapUpdate(saved, newEntry = false, deleted = true, excludeUserId = requesterId) //Notify all users of the deleted entry
        loggingService.log(
            userId = requesterId,
            logType = LogType.MAP_ENTRY_DELETED,
        )
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
        val pagedEntries = allUpdated.drop(start).take(pageSize)

        //One lookup for the whole page instead of one per entry
        val editorNames = userLookupService
            .findAllById(pagedEntries.map { it.updatedBy }.distinct())
            .associate { it.id to it.username }

        val paged = pagedEntries.map {
            it.toMapEntryResponse(updatedByName = editorNames[it.updatedBy] ?: "Unknown")
        }
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
            //AppLogger.info("Legacy map entry import skipped: collection not empty, testing for deserialization error:")
            mapEntryRepository.findAll().first()
            //AppLogger.info("No error")
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
            val categoryName = fixEncoding(entry["Name"] as? String ?: continue)
            if (categoryName in skippedCategories) { skipped++; continue }

            val beschreibung = fixEncoding((entry["Beschreibung"] as? String).orEmpty())
            val createdAt = Instant.fromEpochMilliseconds((entry["CreationTime"] as? String)?.toLongOrNull() ?: 0L)
            val updatedAt = Instant.fromEpochMilliseconds((entry["LastChanged"]  as? String)?.toLongOrNull() ?: 0L)
            val lat = (entry["Latitude"]  as? String)?.toDoubleOrNull() ?: continue
            val lng = (entry["Longitude"] as? String)?.toDoubleOrNull() ?: continue

            val locationData: LocationData
            val name: String
            val description: String

            when (categoryName) {

                "Radar" -> {
                    val speed = speedRegex.find(beschreibung)?.groupValues?.get(1)?.toIntOrNull()
                    locationData = if (beschreibung == "Ampelblitzer") {
                        LocationData.Radar(
                            speedLimit = AttributeValue.IntValue(0),
                            mobile     = AttributeValue.BoolValue(false),
                            redLight   = AttributeValue.BoolValue(true),
                        )
                    } else {
                        LocationData.Radar(
                            speedLimit = AttributeValue.IntValue(speed ?: 0),
                            mobile     = AttributeValue.BoolValue(false),
                            redLight   = AttributeValue.BoolValue(false),
                        )
                    }
                    name        = if (speed != null) "$speed km/h Radar" else "Radar"
                    description = ""
                }

                "Motorradstrecke" -> {
                    locationData = LocationData.MountainStreet(
                        mautFee        = null,
                        heightLimit    = null,
                        closedInWinter = null,
                    )
                    name        = beschreibung.ifBlank { "Motorradstrecke" }
                    description = ""
                }

                "Wheeliespot" -> {
                    locationData = LocationData.Wheeliespot(
                        onlyOnWeekends = null,
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
                    locationData = LocationData.SwimmingLocation(indoor = null, jumpSpot = null, lieDownFriendly = null)
                    name        = beschreibung.ifBlank { "Badespot" }
                    description = ""
                }

                "Partylocation" -> {
                    locationData = LocationData.PartyLocation(entryFee = null)
                    name        = beschreibung.ifBlank { "Partylocation" }
                    description = ""
                }

                "Kebab" -> {
                    locationData = LocationData.FoodKebab(kebabPrice = null)
                    name        = beschreibung.ifBlank { "Kebab" }
                    description = ""
                }

                "Essen" -> {
                    locationData = LocationData.FoodOther(
                        cuisine = AttributeValue.StringValue(beschreibung.ifBlank { "Essen" }),
                    )
                    name        = beschreibung.ifBlank { "Essen" }
                    description = ""
                }

                else -> { skipped++; continue }
            }

            batch.add(
                MapEntry(
                    name         = name,
                    description  = description,
                    coordinates  = LatLong(lat = lat, long = lng),
                    locationData = listOf(locationData),
                    createdBy    = creatorId,
                    createdAt    = createdAt,
                    updatedBy    = creatorId,
                    updatedAt    = updatedAt,
                    deleted      = false,
                )
            )
        }

        mapEntryRepository.saveAll(batch)
        AppLogger.success("Legacy map entry import complete: imported=${batch.size}, skipped=$skipped")
    }


}


private val cp1252 = Charset.forName("windows-1252")

//Reverse wrong encoding from v2 schneaggmap strings ("Seebrünzler" to "SeebrÃƒÂ¼nzler")

fun fixEncoding(s: String): String {
    var current = s

    repeat(5) {
        val fixed = try {
            String(current.toByteArray(cp1252), Charsets.UTF_8)
        } catch (_: Exception) {
            return current
        }

        if (fixed == current) {
            return current
        }

        current = fixed
    }

    return current
}