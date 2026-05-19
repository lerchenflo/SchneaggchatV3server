@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap

import com.fasterxml.jackson.core.type.TypeReference
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.repository.MapEntryRepository
import com.lerchenflo.schneaggchatv3server.repository.SubtypeRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeDefinition
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeValue
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeValueDoc
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.toDoc
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MainType
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntry
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntryResponse
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.Subtype
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.SubtypeResponse
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.toMapEntryResponse
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.toSubtypeResponse
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.Json
import org.bson.types.ObjectId
import org.springframework.context.annotation.Lazy
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

data class SubtypeSyncResponse(
    val updatedSubtypes: List<SubtypeResponse>,
    val deletedSubtypeIds: List<String>,
    val moreSubtypes: Boolean,
)

@Service
class SchneaggmapService(
    private val mapEntryRepository: MapEntryRepository,
    private val subtypeRepository: SubtypeRepository,
    @Lazy private val notificationService: NotificationService,
) {

    // ─── Validation ──────────────────────────────────────────────────────────

    fun validateAttributes(mainType: MainType, attrs: Map<String, AttributeValue>, subtypeNames: List<String> = emptyList()) {
        val defs = mainType.attributeDefinitions.associateBy { it.key }

        for (def in mainType.attributeDefinitions) {
            if (def.required && !attrs.containsKey(def.key)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required attribute: ${def.key}")
            }
        }

        for (rule in mainType.conditionalRules) {
            val triggered = subtypeNames.any { it in rule.requiredIfSubtypeNames }
            if (triggered && !attrs.containsKey(rule.attributeKey)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required attribute '${rule.attributeKey}' for subtype(s): ${rule.requiredIfSubtypeNames.intersect(subtypeNames.toSet()).joinToString()}")
            }
        }

        for ((key, value) in attrs) {
            val def = defs[key]
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown attribute key: $key")

            when (def) {
                is AttributeDefinition.StringDef -> {
                    if (value !is AttributeValue.StringValue)
                        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Attribute '$key' must be a string")
                    def.maxLength?.let { max ->
                        if (value.value.length > max)
                            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Attribute '$key' exceeds max length $max")
                    }
                }
                is AttributeDefinition.IntDef -> {
                    if (value !is AttributeValue.IntValue)
                        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Attribute '$key' must be an int")
                    def.min?.let { if (value.value < it) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Attribute '$key' is below minimum $it") }
                    def.max?.let { if (value.value > it) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Attribute '$key' exceeds maximum $it") }
                }
                is AttributeDefinition.DoubleDef -> {
                    if (value !is AttributeValue.DoubleValue)
                        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Attribute '$key' must be a double")
                    def.min?.let { if (value.value < it) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Attribute '$key' is below minimum $it") }
                    def.max?.let { if (value.value > it) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Attribute '$key' exceeds maximum $it") }
                }
                is AttributeDefinition.BoolDef -> {
                    if (value !is AttributeValue.BoolValue)
                        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Attribute '$key' must be a bool")
                }
            }
        }
    }

    fun validateSubtypes(mainTypeKey: String, subtypeIds: List<ObjectId>) {
        if (subtypeIds.isEmpty()) return
        val subtypes = subtypeRepository.findAllById(subtypeIds)
        val found = subtypes.associateBy { it.id }
        for (id in subtypeIds) {
            val subtype = found[id]
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Subtype not found: ${id.toHexString()}")
            if (subtype.deleted)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Subtype is deleted: ${id.toHexString()}")
            if (subtype.mainTypeKey != mainTypeKey)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Subtype '${subtype.name}' does not belong to main type '$mainTypeKey'")
        }
    }

    private fun resolveSubtypeNames(subtypeIds: List<ObjectId>): List<String> {
        if (subtypeIds.isEmpty()) return emptyList()
        return subtypeRepository.findAllById(subtypeIds).map { it.name }
    }

    // ─── CRUD ─────────────────────────────────────────────────────────────────

    fun createMapEntry(
        mainTypeKey: String,
        subtypeIdStrings: List<String>,
        coordinates: LatLong,
        description: String,
        attributes: Map<String, AttributeValue>,
        requesterId: ObjectId,
    ): MapEntry {
        val mainType = MainType.fromKey(mainTypeKey)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown main type: $mainTypeKey")
        val subtypeIds = subtypeIdStrings.map { ObjectId(it) }
        validateSubtypes(mainTypeKey, subtypeIds)
        val subtypeNames = resolveSubtypeNames(subtypeIds)
        validateAttributes(mainType, attributes, subtypeNames)

        val now = Clock.System.now()
        val entry = mapEntryRepository.save(
            MapEntry(
                mainTypeKey = mainTypeKey,
                subtypeIds = subtypeIds,
                coordinates = coordinates,
                description = description,
                attributes = attributes.mapValues { it.value.toDoc() },
                createdBy = requesterId,
                createdAt = now,
                lastChangedBy = requesterId,
                lastChangedAt = now,
            )
        )
        notificationService.notifyMapUpdate(entry, newEntry = true, deleted = false, changingUserId = requesterId)
        return entry
    }

    fun editMapEntry(
        entryId: ObjectId,
        subtypeIdStrings: List<String>,
        coordinates: LatLong,
        description: String,
        attributes: Map<String, AttributeValue>,
        requesterId: ObjectId,
    ): MapEntry {
        val existing = mapEntryRepository.findById(entryId).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Map entry not found")

        val mainType = MainType.fromKey(existing.mainTypeKey)
            ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unknown main type in stored entry")
        val subtypeIds = subtypeIdStrings.map { ObjectId(it) }
        validateSubtypes(existing.mainTypeKey, subtypeIds)
        val subtypeNames = resolveSubtypeNames(subtypeIds)
        validateAttributes(mainType, attributes, subtypeNames)

        val updated = existing.copy(
            subtypeIds = subtypeIds,
            coordinates = coordinates,
            description = description,
            attributes = attributes.mapValues { it.value.toDoc() },
            lastChangedBy = requesterId,
            lastChangedAt = Clock.System.now(),
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
            lastChangedBy = requesterId,
            lastChangedAt = Clock.System.now(),
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
        val allEntries = mapEntryRepository.findAll()
        val serverIds = allEntries.map { it.id.toHexString() }.toSet()

        val toAdd = allEntries.filter { it.id.toHexString() !in clientMap }
        val toUpdate = allEntries.filter { entry ->
            clientMap[entry.id.toHexString()]?.toLongOrNull()?.let { clientTs ->
                entry.lastChangedAt.toEpochMilliseconds() > clientTs
            } ?: false
        }

        val allUpdated = (toAdd + toUpdate)
            .sortedByDescending { it.lastChangedAt.toEpochMilliseconds() }

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

    // ─── Subtypes ─────────────────────────────────────────────────────────────

    fun listSubtypes(mainTypeKey: String): List<SubtypeResponse> {
        MainType.fromKey(mainTypeKey)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown main type: $mainTypeKey")
        return subtypeRepository.findByMainTypeKey(mainTypeKey)
            .filter { !it.deleted }
            .map { it.toSubtypeResponse() }
    }

    fun subtypeSync(
        clientEntries: List<UserService.IdTimeStamp>,
        page: Int,
        pageSize: Int,
    ): SubtypeSyncResponse {
        val clientMap = clientEntries.associate { it.id to it.timeStamp }
        val allSubtypes = subtypeRepository.findAll()
        val serverIds = allSubtypes.map { it.id.toHexString() }.toSet()

        val toAdd = allSubtypes.filter { it.id.toHexString() !in clientMap }
        val toUpdate = allSubtypes.filter { subtype ->
            clientMap[subtype.id.toHexString()]?.toLongOrNull()?.let { clientTs ->
                subtype.lastChangedAt.toEpochMilliseconds() > clientTs
            } ?: false
        }

        val allUpdated = (toAdd + toUpdate)
            .sortedByDescending { it.lastChangedAt.toEpochMilliseconds() }

        val start = page * pageSize
        val paged = allUpdated.drop(start).take(pageSize).map { it.toSubtypeResponse() }
        val moreSubtypes = (start + pageSize) < allUpdated.size

        val deletedSubtypeIds = if (page == 0) clientMap.keys.filter { it !in serverIds } else emptyList()

        return SubtypeSyncResponse(
            updatedSubtypes = paged,
            deletedSubtypeIds = deletedSubtypeIds,
            moreSubtypes = moreSubtypes,
        )
    }

    fun createSubtype(mainTypeKey: String, name: String, requesterId: ObjectId): SubtypeResponse {
        MainType.fromKey(mainTypeKey)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown main type: $mainTypeKey")
        val existing = subtypeRepository.findByMainTypeKeyAndNameIgnoreCase(mainTypeKey, name)
        if (existing != null && !existing.deleted)
            throw ResponseStatusException(HttpStatus.CONFLICT, "Subtype '$name' already exists for type '$mainTypeKey'")

        val now = Clock.System.now()
        val subtype = subtypeRepository.save(
            Subtype(
                mainTypeKey = mainTypeKey,
                name = name,
                createdBy = requesterId,
                createdAt = now,
                lastChangedAt = now,
            )
        )
        notificationService.notifySubtypeCreated(subtype, changingUserId = requesterId)
        return subtype.toSubtypeResponse()
    }

    // ─── Boot hooks ───────────────────────────────────────────────────────────

    fun seedSubtypes(creatorId: ObjectId) {
        val now = Clock.System.now()
        for (mainType in MainType.entries) {
            for (name in mainType.seedSubtypes) {
                val existing = subtypeRepository.findByMainTypeKeyAndNameIgnoreCase(mainType.key, name)
                if (existing == null) {
                    subtypeRepository.save(
                        Subtype(
                            mainTypeKey = mainType.key,
                            name = name,
                            createdBy = creatorId,
                            createdAt = now,
                        )
                    )
                    AppLogger.info("Seeded subtype '$name' for main type '${mainType.key}'")
                }
            }
        }
    }

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
        val nameMapping = mapOf(
            "Radar"             to (MainType.STREET        to "RADAR"),
            "Polizei"           to (MainType.STREET        to "POLIZEI"),
            "Motorradstrecke"   to (MainType.STREET        to "MOTORRADSTRECKE"),
            "Wheeliespot"       to (MainType.STREET        to "WHEELIESPOT"),
            "Sehenswuerdigkeit" to (MainType.SIGHTSEEINGPLACE to "SEHENSWUERDIGKEIT"),
            "Badespot"          to (MainType.SIGHTSEEINGPLACE to "BADESPOT"),
            "Partylocation"     to (MainType.SIGHTSEEINGPLACE to "PARTYLOCATION"),
            "Kebab"             to (MainType.FOODPLACE     to "KEBAB"),
            "Essen"             to (MainType.FOODPLACE     to "ESSEN"),
        )

        val speedRegex = Regex("""^(\d+)""")
        val batch = mutableListOf<MapEntry>()
        var skipped = 0

        for (entry in raw) {
            val name = entry["Name"] as? String ?: continue
            if (name in skippedCategories) { skipped++; continue }

            val (mainType, subtypeName) = nameMapping[name] ?: run { skipped++; continue }
            val subtype = subtypeRepository.findByMainTypeKeyAndNameIgnoreCase(mainType.key, subtypeName) ?: continue

            val beschreibung = (entry["Beschreibung"] as? String).orEmpty()
            val createdAt = Instant.fromEpochMilliseconds((entry["CreationTime"] as? String)?.toLongOrNull() ?: 0L)
            val lastChangedAt = Instant.fromEpochMilliseconds((entry["LastChanged"] as? String)?.toLongOrNull() ?: 0L)
            val lat = (entry["Latitude"] as? String)?.toDoubleOrNull() ?: continue
            val lng = (entry["Longitude"] as? String)?.toDoubleOrNull() ?: continue

            val attributes: Map<String, AttributeValueDoc>
            val description: String

            if (mainType == MainType.STREET && subtypeName == "RADAR") {
                val parsed = speedRegex.find(beschreibung)?.groupValues?.get(1)?.toIntOrNull()
                if (parsed != null && parsed > 0) {
                    attributes = mapOf("speedLimit" to AttributeValueDoc(type = "int", intValue = parsed))
                    description = ""
                } else {
                    attributes = emptyMap()
                    description = "needs to be filled in"
                }
            } else {
                attributes = emptyMap()
                description = beschreibung
            }

            batch.add(
                MapEntry(
                    mainTypeKey = mainType.key,
                    subtypeIds = listOf(subtype.id),
                    coordinates = LatLong(lat = lat, long = lng),
                    description = description,
                    attributes = attributes,
                    createdBy = creatorId,
                    createdAt = createdAt,
                    lastChangedBy = creatorId,
                    lastChangedAt = lastChangedAt,
                )
            )
        }

        mapEntryRepository.saveAll(batch)
        AppLogger.success("Legacy map entry import complete: imported=${batch.size}, skipped=$skipped")
    }
}
