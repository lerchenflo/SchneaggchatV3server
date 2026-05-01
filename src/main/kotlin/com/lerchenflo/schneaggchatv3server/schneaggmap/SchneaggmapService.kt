@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap

import com.lerchenflo.schneaggchatv3server.repository.MapEntryRepository
import com.lerchenflo.schneaggchatv3server.repository.SubtypeRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeDefinition
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeValue
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MainType
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.Subtype
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Service
class SchneaggmapService(
    private val mapEntryRepository: MapEntryRepository,
    private val subtypeRepository: SubtypeRepository,
) {

    fun validateAttributes(mainType: MainType, attrs: Map<String, AttributeValue>) {
        val defs = mainType.attributeDefinitions.associateBy { it.key }

        //Check for missing keys in attrs
        for (def in mainType.attributeDefinitions) {
            if (def.required && !attrs.containsKey(def.key)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required attribute: ${def.key}")
            }
        }

        for ((key, value) in attrs) {
            val def = defs[key]
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown attribute key: $key")

            when (def) {
                is AttributeDefinition.StringDef -> {
                    if (value !is AttributeValue.StringValue) {
                        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Attribute '$key' must be a string")
                    }
                    def.maxLength?.let { max ->
                        if (value.value.length > max) {
                            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Attribute '$key' exceeds max length $max")
                        }
                    }
                }
                is AttributeDefinition.IntDef -> {
                    if (value !is AttributeValue.IntValue) {
                        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Attribute '$key' must be an int")
                    }
                    def.min?.let { min ->
                        if (value.value < min) {
                            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Attribute '$key' is below minimum $min")
                        }
                    }
                    def.max?.let { max ->
                        if (value.value > max) {
                            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Attribute '$key' exceeds maximum $max")
                        }
                    }
                }
                is AttributeDefinition.BoolDef -> {
                    if (value !is AttributeValue.BoolValue) {
                        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Attribute '$key' must be a bool")
                    }
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
            if (subtype.deleted) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Subtype is deleted: ${id.toHexString()}")
            }
            if (subtype.mainTypeKey != mainTypeKey) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Subtype '${subtype.name}' does not belong to main type '$mainTypeKey'")
            }
        }
    }

    fun seedSubtypes() {
        val now = Clock.System.now()
        for (mainType in MainType.entries) {
            for (name in mainType.seedSubtypes) {
                val existing = subtypeRepository.findByMainTypeKeyAndNameIgnoreCase(mainType.key, name)
                if (existing == null) {
                    subtypeRepository.save(
                        Subtype(
                            mainTypeKey = mainType.key,
                            name = name,
                            createdBy = null,
                            createdAt = now,
                        )
                    )
                    AppLogger.info("Seeded subtype '$name' for main type '${mainType.key}'")
                }
            }
        }
    }
}
