package com.lerchenflo.schneaggchatv3server.schneaggmap.model

data class MainTypeResponse(
    val key: String,
    val displayName: String,
    val attributeDefinitions: List<AttributeDefinition>,
    val conditionalRules: List<ConditionalRule>,
)

fun MainType.toMainTypeResponse(): MainTypeResponse = MainTypeResponse(
    key = key,
    displayName = displayName,
    attributeDefinitions = attributeDefinitions,
    conditionalRules = conditionalRules,
)
