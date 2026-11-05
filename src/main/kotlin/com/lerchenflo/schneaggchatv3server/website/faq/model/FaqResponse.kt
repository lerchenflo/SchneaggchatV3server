package com.lerchenflo.schneaggchatv3server.website.faq.model

data class FaqTextResponse(
    val question: String,
    val answer: String,
)

/**
 * Public shape - carries all three languages at once so the page can re-render on a language
 * switch without a second request, the way the donations page re-formats its dates.
 */
data class FaqEntryResponse(
    val id: String,
    val category: String,
    val sortOrder: Int,
    val german: FaqTextResponse,
    val austrian: FaqTextResponse?,
    val english: FaqTextResponse?,
)

data class FaqListResponse(
    val entries: List<FaqEntryResponse>,
)

/** Admin shape - includes soft-deleted rows and audit timestamps. */
data class AdminFaqEntryResponse(
    val id: String,
    val category: String,
    val sortOrder: Int,
    val german: FaqTextResponse,
    val austrian: FaqTextResponse?,
    val english: FaqTextResponse?,
    val deleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
