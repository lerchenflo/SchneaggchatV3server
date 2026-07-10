package com.lerchenflo.schneaggchatv3server.games.model

enum class Difficulty {
    LOW,
    MEDIUM,
    HIGH;

    companion object {
        fun fromId(id: String): Difficulty? = entries.find { it.name.equals(id, ignoreCase = true) }
    }
}
