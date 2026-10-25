@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.announcement.model

import kotlin.time.ExperimentalTime

/** Shapes match the app's `announcement/data/remote/dto/`. Times are epoch millis. */
data class AnnouncementTextResponse(
    val title: String,
    val body: String,
)

data class AnnouncementResponse(
    val id: Long,
    val german: AnnouncementTextResponse,
    val austrian: AnnouncementTextResponse?,
    val english: AnnouncementTextResponse?,
    val italian: AnnouncementTextResponse?,
    val expiresAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    // Only filled for the admin endpoints
    val creatorName: String?,
)

/** "Nothing to show" is a normal reply with a null [announcement], not an empty body. */
data class LatestAnnouncementResponse(
    val announcement: AnnouncementResponse?,
)

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
