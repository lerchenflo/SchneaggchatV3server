@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.faq.model

import kotlin.time.ExperimentalTime

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

fun FaqText.toFaqTextResponse(): FaqTextResponse = FaqTextResponse(
    question = question,
    answer = answer,
)

fun FaqEntry.toFaqEntryResponse(): FaqEntryResponse = FaqEntryResponse(
    id = id.toHexString(),
    category = category.name,
    sortOrder = sortOrder,
    german = german.toFaqTextResponse(),
    austrian = austrian?.toFaqTextResponse(),
    english = english?.toFaqTextResponse(),
)

fun FaqEntry.toAdminFaqEntryResponse(): AdminFaqEntryResponse = AdminFaqEntryResponse(
    id = id.toHexString(),
    category = category.name,
    sortOrder = sortOrder,
    german = german.toFaqTextResponse(),
    austrian = austrian?.toFaqTextResponse(),
    english = english?.toFaqTextResponse(),
    deleted = deleted,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
)
