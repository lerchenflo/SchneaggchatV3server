@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.admin

import com.lerchenflo.schneaggchatv3server.admin.model.ConnectedUsersSnapshot
import com.lerchenflo.schneaggchatv3server.core.security.AdminGuard
import com.lerchenflo.schneaggchatv3server.core.security.JwtService
import com.lerchenflo.schneaggchatv3server.donations.DonationService
import com.lerchenflo.schneaggchatv3server.donations.model.AdminDonationResponse
import com.lerchenflo.schneaggchatv3server.schneaggmap.MapChangeLogEditor
import com.lerchenflo.schneaggchatv3server.schneaggmap.MapChangeLogPage
import com.lerchenflo.schneaggchatv3server.schneaggmap.MapEntryVersionService
import com.lerchenflo.schneaggchatv3server.util.LogPage
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@RestController
@RequestMapping("/admin/api")
class AdminController(
    private val adminGuard: AdminGuard,
    private val jwtService: JwtService,
    private val mapEntryVersionService: MapEntryVersionService,
    private val donationService: DonationService,
    private val loggingService: LoggingService,
    private val adminEventService: AdminEventService,
) {

    // ─── Map change log ─────────────────────────────────────────────────────

    @GetMapping("/map/changelog")
    fun getMapChangeLog(
        @RequestParam(required = false) editedBy: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") pageSize: Int,
    ): MapChangeLogPage {
        adminGuard.requireAdmin()
        require(ValidationUtils.validatePaginationPage(page)) { "Invalid page number" }
        require(ValidationUtils.validatePaginationPageSize(pageSize)) { "Invalid page size" }
        val editedByObjectId = editedBy?.let {
            require(ValidationUtils.validateObjectId(it)) { "Invalid editedBy id" }
            ObjectId(it)
        }
        return mapEntryVersionService.getChangeLog(editedByObjectId, page, pageSize)
    }

    @GetMapping("/map/changelog/editors")
    fun getMapChangeLogEditors(): List<MapChangeLogEditor> {
        adminGuard.requireAdmin()
        return mapEntryVersionService.getEditors()
    }

    // ─── Donations ───────────────────────────────────────────────────────────

    data class DonationRequest(
        @field:NotBlank(message = "Name must not be blank")
        @field:Size(max = 100, message = "Name too long")
        val name: String,
        val amountCents: Long,
        val donatedAt: Long,
        @field:Size(max = 500, message = "Message too long")
        val message: String?,
    )

    @GetMapping("/donations")
    fun getAllDonations(): List<AdminDonationResponse> {
        adminGuard.requireAdmin()
        return donationService.listAll()
    }

    @PostMapping("/donations")
    fun createDonation(@Valid @RequestBody request: DonationRequest): AdminDonationResponse {
        adminGuard.requireAdmin()
        return donationService.create(
            name = request.name,
            amountCents = request.amountCents,
            donatedAt = Instant.fromEpochMilliseconds(request.donatedAt),
            message = request.message,
        )
    }

    @PutMapping("/donations/{id}")
    fun updateDonation(@PathVariable id: String, @Valid @RequestBody request: DonationRequest): AdminDonationResponse {
        adminGuard.requireAdmin()
        require(ValidationUtils.validateObjectId(id)) { "Invalid donation id" }
        return donationService.update(
            id = ObjectId(id),
            name = request.name,
            amountCents = request.amountCents,
            donatedAt = Instant.fromEpochMilliseconds(request.donatedAt),
            message = request.message,
        )
    }

    @DeleteMapping("/donations/{id}")
    fun deleteDonation(@PathVariable id: String) {
        adminGuard.requireAdmin()
        require(ValidationUtils.validateObjectId(id)) { "Invalid donation id" }
        donationService.delete(ObjectId(id))
    }

    // ─── Connected users ─────────────────────────────────────────────────────

    @GetMapping("/connected-users")
    fun getConnectedUsers(): ConnectedUsersSnapshot {
        adminGuard.requireAdmin()
        return adminEventService.buildSnapshot()
    }

    /**
     * Live updates for the panel. A browser EventSource can't set an Authorization header, so the
     * admin client consumes this with fetch() + a stream reader instead of the EventSource API -
     * this endpoint therefore stays behind the same requireAdmin() check as everything else here.
     *
     * The stream is bound to the lifetime of the access token that opened it: an open connection has
     * no per-request re-auth point, so without that it would keep pushing live data long after the
     * token expired or the admin logged out.
     */
    @GetMapping("/connected-users/stream", produces = ["text/event-stream"])
    fun streamConnectedUsers(@RequestHeader("Authorization") authorization: String): SseEmitter {
        val adminId = adminGuard.requireAdmin()
        val expiresAtMillis = jwtService.getExpiryMillisFromToken(authorization)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token has no expiry")
        return adminEventService.register(adminId, expiresAtMillis)
    }

    // ─── Logs ────────────────────────────────────────────────────────────────

    @GetMapping("/logs")
    fun getLogs(
        @RequestParam(defaultValue = "EXCEPTION_THROWN") logType: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") pageSize: Int,
    ): LogPage {
        adminGuard.requireAdmin()
        require(ValidationUtils.validatePaginationPage(page)) { "Invalid page number" }
        require(ValidationUtils.validatePaginationPageSize(pageSize)) { "Invalid page size" }

        val parsedLogType = if (logType.equals("ALL", ignoreCase = true)) {
            null
        } else {
            runCatching { LogType.valueOf(logType) }.getOrElse {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown log type: $logType")
            }
        }

        return loggingService.getLogs(parsedLogType, page, pageSize)
    }

    @GetMapping("/logs/types")
    fun getLogTypes(): List<String> {
        adminGuard.requireAdmin()
        return LogType.entries.map { it.name }
    }
}
