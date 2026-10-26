@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap

import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.repository.MapEntryRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.*
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import com.lerchenflo.schneaggchatv3server.util.withOptimisticRetry
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.collections.forEach
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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
            // Optional attributes may be absent, but a present value is always type/range checked
            val value = data.getValueByKey(def.key)
            if (value == null) {
                if (def.required) errors += "${def.key}: required field is missing"
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

                is AttributeDefinition.EnumDef -> {
                    val v = (value as? AttributeValue.StringValue)?.value
                        ?: run { errors += "${def.key}: expected enum string"; continue }
                    if (v !in def.options) errors += "${def.key}: '$v' is not one of ${def.options}"
                }

                is AttributeDefinition.DateTimeDef -> {
                    if (value !is AttributeValue.LongValue)
                        errors += "${def.key}: expected datetime (long)"
                }

                is AttributeDefinition.PriceDef -> {
                    val v = (value as? AttributeValue.DoubleValue)?.value
                        ?: run { errors += "${def.key}: expected price (double)"; continue }
                    if (v < 0.0) errors += "${def.key}: $v is negative"
                    def.max?.let { if (v > it) errors += "${def.key}: $v exceeds maximum $it" }
                }

                is AttributeDefinition.DistanceDef -> {
                    val v = (value as? AttributeValue.DoubleValue)?.value
                        ?: run { errors += "${def.key}: expected distance (double)"; continue }
                    if (v < 0.0) errors += "${def.key}: $v is negative"
                    def.max?.let { if (v > it) errors += "${def.key}: $v exceeds maximum $it" }
                }

                is AttributeDefinition.RatingDef -> {
                    val v = (value as? AttributeValue.IntValue)?.value
                        ?: run { errors += "${def.key}: expected rating (int)"; continue }
                    if (v !in def.min..def.max) errors += "${def.key}: $v is not within ${def.min}..${def.max}"
                }

                is AttributeDefinition.SecretDef -> {
                    val v = (value as? AttributeValue.StringValue)?.value
                        ?: run { errors += "${def.key}: expected secret string"; continue }
                    def.maxLength?.let { if (v.length > it) errors += "${def.key}: length ${v.length} exceeds maximum $it" }
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

            AppLogger.info("MAP: ENTRY \"$name\" ${if (existing != null) "updated" else "created"}", user = requesterId)

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

}
