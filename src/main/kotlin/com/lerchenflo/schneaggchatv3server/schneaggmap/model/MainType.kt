package com.lerchenflo.schneaggchatv3server.schneaggmap.model

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeDefinition.DoubleDef
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeDefinition.IntDef

data class ConditionalRule(val attributeKey: String, val requiredIfSubtypeNames: Set<String>)

enum class MainType(
    val key: String,
    val displayName: String,
    val attributeDefinitions: List<AttributeDefinition>,
    val conditionalRules: List<ConditionalRule>,
    val seedSubtypes: List<String>,
) {
    STREET(
        key = "street",
        displayName = "Street",
        attributeDefinitions = listOf(
            IntDef(key = "speedLimit", required = false, min = 0, max = 400),
        ),
        conditionalRules = listOf(
            ConditionalRule(attributeKey = "speedLimit", requiredIfSubtypeNames = setOf("RADAR")),
        ),
        seedSubtypes = listOf("RADAR", "POLIZEI", "MOTORRADSTRECKE", "WHEELIESPOT"),
    ),
    SIGHTSEEINGPLACE(
        key = "sightseeingplace",
        displayName = "Sightseeing place",
        attributeDefinitions = listOf(
            DoubleDef(key = "entryfee", required = false),
        ),
        conditionalRules = emptyList(),
        seedSubtypes = listOf("SEHENSWUERDIGKEIT", "AUSSICHTSPUNKT", "BADESPOT", "PARTYLOCATION"),
    ),
    FOODPLACE(
        key = "foodplace",
        displayName = "Food place",
        attributeDefinitions = emptyList(),
        conditionalRules = emptyList(),
        seedSubtypes = listOf("KEBAB", "ESSEN"),
    );

    companion object {
        fun fromKey(k: String): MainType? = entries.find { it.key == k }
    }
}
