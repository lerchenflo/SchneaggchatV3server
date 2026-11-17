package com.lerchenflo.schneaggchatv3server.announcement.model

fun AnnouncementText.toAnnouncementTextResponse(): AnnouncementTextResponse = AnnouncementTextResponse(
    title = title,
    body = body,
)

/** [withCreator]: only the admin endpoints show who wrote it. */
fun Announcement.toAnnouncementResponse(withCreator: Boolean): AnnouncementResponse = AnnouncementResponse(
    id = id,
    german = german.toAnnouncementTextResponse(),
    austrian = austrian?.toAnnouncementTextResponse(),
    english = english?.toAnnouncementTextResponse(),
    italian = italian?.toAnnouncementTextResponse(),
    expiresAt = expiresAt.toEpochMilliseconds(),
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
    creatorId = createdBy.toHexString().takeIf { withCreator },
)
