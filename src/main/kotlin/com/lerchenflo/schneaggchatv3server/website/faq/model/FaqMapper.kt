package com.lerchenflo.schneaggchatv3server.website.faq.model

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
