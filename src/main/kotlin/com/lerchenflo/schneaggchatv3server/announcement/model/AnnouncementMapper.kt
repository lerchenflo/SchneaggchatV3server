package com.lerchenflo.schneaggchatv3server.announcement.model

fun AnnouncementText.toAnnouncementTextResponse(): AnnouncementTextResponse = AnnouncementTextResponse(
    title = title,
    body = body,
)

fun Announcement.toAnnouncementResponse(creatorName: String?): AnnouncementResponse = AnnouncementResponse(
    id = id,
    german = german.toAnnouncementTextResponse(),
    austrian = austrian?.toAnnouncementTextResponse(),
    english = english?.toAnnouncementTextResponse(),
    italian = italian?.toAnnouncementTextResponse(),
    expiresAt = expiresAt.toEpochMilliseconds(),
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
    creatorName = creatorName,
)
