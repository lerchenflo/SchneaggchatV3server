package com.lerchenflo.schneaggchatv3server.util

import com.fasterxml.jackson.databind.ObjectMapper

object Json {
    val mapper: ObjectMapper = ObjectMapper()
        .findAndRegisterModules()
}