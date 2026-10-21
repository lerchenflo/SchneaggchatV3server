@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.announcement

import com.lerchenflo.schneaggchatv3server.announcement.model.AnnouncementResponse
import com.lerchenflo.schneaggchatv3server.announcement.model.AnnouncementText
import com.lerchenflo.schneaggchatv3server.announcement.model.LatestAnnouncementResponse
import com.lerchenflo.schneaggchatv3server.core.security.AdminGuard
import com.lerchenflo.schneaggchatv3server.core.security.requireAuth
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Admin announcements for the app. The admin routes sit under the normal app API, not the website's
 * `/chefdev/api`, because admins manage them from the app's developer settings. The app shows that
 * form to everyone with developer settings enabled - [AdminGuard.requireAdmin] is the only check,
 * and the role itself is never sent to the app.
 *
 * No bean validation (@Valid) on the bodies: texts and expiry are checked in [AnnouncementService],
 * after the admin check, so a non-admin sending a well-formed body gets the guard's 404 instead of
 * a validation error that confirms the route exists.
 */
@RestController
@RequestMapping("/announcements")
class AnnouncementController(
    private val announcementService: AnnouncementService,
    private val adminGuard: AdminGuard,
) {

    data class AnnouncementTextRequest(
        val title: String,
        // The app leaves out an empty text
        val body: String = "",
    )

    /** Create ([id] null) or edit ([id] set). `austrian` is the app's Vori, [expiresAt] epoch millis. */
    data class AnnouncementRequest(
        val id: Long? = null,
        val german: AnnouncementTextRequest,
        val austrian: AnnouncementTextRequest? = null,
        val english: AnnouncementTextRequest? = null,
        val italian: AnnouncementTextRequest? = null,
        val expiresAt: Long,
    )

    @GetMapping("/latest")
    fun getLatest(): LatestAnnouncementResponse {
        val requestingUserId = requireAuth()

        return LatestAnnouncementResponse(
            announcement = announcementService.getLatestFor(requestingUserId)
        )
    }

    @GetMapping("/admin/list")
    fun listAll(): List<AnnouncementResponse> {
        adminGuard.requireAdmin()
        return announcementService.listAll()
    }

    @PostMapping("/admin/create")
    fun create(@RequestBody request: AnnouncementRequest): AnnouncementResponse {
        val adminId = adminGuard.requireAdmin()
        require(request.id == null) { "A new announcement must not carry an id" }

        return announcementService.create(
            adminId = adminId,
            german = request.german.toAnnouncementText(),
            austrian = request.austrian?.toAnnouncementText(),
            english = request.english?.toAnnouncementText(),
            italian = request.italian?.toAnnouncementText(),
            expiresAt = Instant.fromEpochMilliseconds(request.expiresAt),
        )
    }

    @PostMapping("/admin/update")
    fun update(@RequestBody request: AnnouncementRequest): AnnouncementResponse {
        adminGuard.requireAdmin()
        val id = requireNotNull(request.id) { "Announcement id missing" }

        return announcementService.update(
            id = id,
            german = request.german.toAnnouncementText(),
            austrian = request.austrian?.toAnnouncementText(),
            english = request.english?.toAnnouncementText(),
            italian = request.italian?.toAnnouncementText(),
            expiresAt = Instant.fromEpochMilliseconds(request.expiresAt),
        )
    }

    @DeleteMapping("/admin/delete")
    fun delete(@RequestParam("id") id: Long) {
        adminGuard.requireAdmin()
        announcementService.delete(id)
    }

    private fun AnnouncementTextRequest.toAnnouncementText(): AnnouncementText =
        AnnouncementText(title = title, body = body)
}
