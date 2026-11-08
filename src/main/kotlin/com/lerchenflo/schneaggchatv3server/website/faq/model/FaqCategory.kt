package com.lerchenflo.schneaggchatv3server.website.faq.model

/**
 * Declaration order is the order the sections appear on the FAQ page. [germanLabel] is the heading
 * the server renders, the same text as `faq_category_*` in `strings-de.xml` and the fallback in `faq.js`.
 */
enum class FaqCategory(val germanLabel: String) {
    GENERAL("Allgemein"),
    ACCOUNT("Account"),
    CHATS("Chats"),
    NOTIFICATIONS("Benachrichtigungen"),
    MAP("Karte"),
    PRIVACY("Datenschutz"),
    TECHNICAL("Technisches");

    companion object {
        fun fromId(id: String): FaqCategory? = entries.find { it.name.equals(id, ignoreCase = true) }
    }
}
