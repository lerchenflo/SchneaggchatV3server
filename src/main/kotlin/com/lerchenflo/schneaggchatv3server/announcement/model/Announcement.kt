@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.announcement.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A notice an admin writes in the app's developer settings. Every user except the test account
 * sees the newest active one as a dismissible banner, in their own language.
 *
 * [id] is a running number handed out by [com.lerchenflo.schneaggchatv3server.announcement.AnnouncementLookupService.nextId]
 * and never reused. The read state is only `PersonalUserSettings.lastDismissedAnnouncementId`, so
 * an edit keeps the number: whoever dismissed it never sees it again.
 *
 * German is mandatory because the app falls back to it whenever a translation is missing.
 * `austrian` is the app's Vori.
 */
@TypeAlias("announcement")
@Document("announcements")
@CompoundIndex(name = "id_expiresat_idx", def = "{'_id': -1, 'expiresAt': 1}") // newest active one
data class Announcement(
    @Id val id: Long,

    val german: AnnouncementText,
    val austrian: AnnouncementText? = null,
    val english: AnnouncementText? = null,
    val italian: AnnouncementText? = null,

    val expiresAt: Instant,

    val createdBy: ObjectId,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AnnouncementText(
    val title: String,
    val body: String,
)
