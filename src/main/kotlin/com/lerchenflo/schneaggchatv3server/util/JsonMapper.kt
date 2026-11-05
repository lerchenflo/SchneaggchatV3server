package com.lerchenflo.schneaggchatv3server.util

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.json.JsonMapper

/**
 * Shared Jackson 3 mapper for Mongo converters, socket messages and push payloads (the HTTP layer
 * uses Spring Boot's own Jackson 3 mapper). The disabled features keep the Jackson 2 behaviour the
 * stored data and the socket wire were written with.
 */
object Json {
    val mapper: JsonMapper = JsonMapper.builder()
        .findAndAddModules()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .build()
}
