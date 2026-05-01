package com.lerchenflo.schneaggchatv3server.schneaggmap.model

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeDefinition.IntDef
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeDefinition.StringDef

enum class MainType(
    val key: String,
    val displayName: String,
    val attributeDefinitions: List<AttributeDefinition>,
    val seedSubtypes: List<String>,
) {
    RADAR(
        key = "radar",
        displayName = "Radar",
        attributeDefinitions = listOf(
            IntDef(key = "speedLimit", required = true, min = 0, max = 400),
            StringDef(key = "note", required = false, maxLength = 200),
        ),
        seedSubtypes = listOf("REDLIGHT", "SPEED", "MOBILE", "LARGECONTROL"),
    );

    companion object {
        fun fromKey(k: String): MainType? = entries.find { it.key == k }
    }
}
