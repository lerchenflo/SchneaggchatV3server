package com.lerchenflo.schneaggchatv3server.website.faq.model

/** Declaration order is the order the sections appear on the FAQ page. */
enum class FaqCategory {
    GENERAL,
    ACCOUNT,
    CHATS,
    MAP,
    PRIVACY,
    TECHNICAL;

    companion object {
        fun fromId(id: String): FaqCategory? = entries.find { it.name.equals(id, ignoreCase = true) }
    }
}
