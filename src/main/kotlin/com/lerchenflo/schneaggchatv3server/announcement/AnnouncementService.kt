@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.announcement

import com.lerchenflo.schneaggchatv3server.announcement.model.Announcement
import com.lerchenflo.schneaggchatv3server.announcement.model.AnnouncementResponse
import com.lerchenflo.schneaggchatv3server.announcement.model.AnnouncementText
import com.lerchenflo.schneaggchatv3server.announcement.model.toAnnouncementResponse
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Admin announcements. No push and no socket message: the app asks for the newest one on start,
 * and a dismissal reaches the user's other devices through the settings update
 * (`UserService.updateSettings` -> `notifyUserUpdate`).
 */
@Service
class AnnouncementService(
    private val announcementLookupService: AnnouncementLookupService,
    private val userLookupService: UserLookupService,
) {

    /**
     * The newest active announcement [userId] has not dismissed yet, or null. Compared against
     * the stored number on the server, so a second device starting before its settings sync
     * arrived cannot briefly show an announcement already dismissed elsewhere.
     */
    fun getLatestFor(userId: ObjectId): AnnouncementResponse? {
        val user = userLookupService.findById(userId) ?: return null

        //App store reviewers never see a notice meant for real users
        if (user.username == UserService.TESTACCOUNT_USERNAME) return null

        return announcementLookupService.findNewestActive(
            aboveId = user.settings.lastDismissedAnnouncementId,
            now = Clock.System.now(),
        )?.toAnnouncementResponse(creatorName = null)
    }

    fun listAll(): List<AnnouncementResponse> {
        val announcements = announcementLookupService.findAllNewestFirst()
        val creatorNames = userLookupService.findAllById(announcements.map { it.createdBy }.distinct())
            .associate { it.id to it.username }

        return announcements.map { it.toAnnouncementResponse(creatorName = creatorNames[it.createdBy]) }
    }

    fun create(
        adminId: ObjectId,
        german: AnnouncementText,
        austrian: AnnouncementText?,
        english: AnnouncementText?,
        italian: AnnouncementText?,
        expiresAt: Instant,
    ): AnnouncementResponse {
        val now = Clock.System.now()
        require(ValidationUtils.validateAnnouncementExpiry(expiresAt, now)) { "Expiry must be in the future and at most 365 days ahead" }

        val validGerman = validated(german, "German")
        val validAustrian = optionalTranslation(austrian, "Vori")
        val validEnglish = optionalTranslation(english, "English")
        val validItalian = optionalTranslation(italian, "Italian")

        //Only take a number once everything is valid - a rejected request must not burn one
        val saved = announcementLookupService.save(
            Announcement(
                id = announcementLookupService.nextId(),
                german = validGerman,
                austrian = validAustrian,
                english = validEnglish,
                italian = validItalian,
                expiresAt = expiresAt,
                createdBy = adminId,
                createdAt = now,
                updatedAt = now,
            )
        )
        return saved.toAnnouncementResponse(creatorName = userLookupService.findById(saved.createdBy)?.username)
    }

    /** Edits texts and expiry. Keeps the number, so users who dismissed it never see it again. */
    fun update(
        id: Long,
        german: AnnouncementText,
        austrian: AnnouncementText?,
        english: AnnouncementText?,
        italian: AnnouncementText?,
        expiresAt: Instant,
    ): AnnouncementResponse {
        val now = Clock.System.now()
        require(ValidationUtils.validateAnnouncementExpiry(expiresAt, now)) { "Expiry must be in the future and at most 365 days ahead" }

        //400, not 404: the app reads a 404 from an admin route as "not an admin"
        val existing = announcementLookupService.findById(id)
            ?: throw IllegalArgumentException("Announcement $id not found")

        val saved = announcementLookupService.save(
            existing.copy(
                german = validated(german, "German"),
                austrian = optionalTranslation(austrian, "Vori"),
                english = optionalTranslation(english, "English"),
                italian = optionalTranslation(italian, "Italian"),
                expiresAt = expiresAt,
                updatedAt = now,
            )
        )
        return saved.toAnnouncementResponse(creatorName = userLookupService.findById(saved.createdBy)?.username)
    }

    /** Hard delete. Deleting an id that is already gone is a no-op, e.g. two admins at once. */
    fun delete(id: Long) {
        announcementLookupService.deleteById(id)
    }

    /** A language counts as filled when its title is filled; anything else is stored as absent. */
    private fun optionalTranslation(text: AnnouncementText?, language: String): AnnouncementText? {
        if (text == null || text.title.isBlank()) return null
        return validated(text, language)
    }

    private fun validated(text: AnnouncementText, language: String): AnnouncementText {
        val trimmed = AnnouncementText(title = text.title.trim(), body = text.body.trim())
        require(ValidationUtils.validateAnnouncementTitle(trimmed.title)) { "Invalid $language title: required, at most 80 characters" }
        require(ValidationUtils.validateAnnouncementBody(trimmed.body)) { "Invalid $language text: at most 500 characters" }
        return trimmed
    }
}
