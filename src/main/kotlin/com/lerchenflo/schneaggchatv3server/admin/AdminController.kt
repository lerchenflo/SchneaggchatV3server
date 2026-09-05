@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.admin

import com.lerchenflo.schneaggchatv3server.admin.model.ConnectedUsersSnapshot
import com.lerchenflo.schneaggchatv3server.core.security.AdminGuard
import com.lerchenflo.schneaggchatv3server.core.security.JwtService
import com.lerchenflo.schneaggchatv3server.donations.DonationService
import com.lerchenflo.schneaggchatv3server.donations.model.AdminDonationResponse
import com.lerchenflo.schneaggchatv3server.faq.FaqService
import com.lerchenflo.schneaggchatv3server.faq.model.AdminFaqEntryResponse
import com.lerchenflo.schneaggchatv3server.faq.model.FaqCategory
import com.lerchenflo.schneaggchatv3server.faq.model.FaqText
import com.lerchenflo.schneaggchatv3server.games.model.Difficulty
import com.lerchenflo.schneaggchatv3server.games.model.Game
import com.lerchenflo.schneaggchatv3server.schneaggmap.MapChangeLogEditor
import com.lerchenflo.schneaggchatv3server.schneaggmap.MapChangeLogPage
import com.lerchenflo.schneaggchatv3server.schneaggmap.MapEntryVersionService
import com.lerchenflo.schneaggchatv3server.util.LogPage
import com.lerchenflo.schneaggchatv3server.util.LogSort
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
@RequestMapping("/chefdev/api")
class AdminController(
    private val adminGuard: AdminGuard,
    private val jwtService: JwtService,
    private val mapEntryVersionService: MapEntryVersionService,
    private val donationService: DonationService,
    private val faqService: FaqService,
    private val loggingService: LoggingService,
    private val adminEventService: AdminEventService,
    private val adminScoreService: AdminScoreService,
    private val adminUserService: AdminUserService,
    private val friendsTreeService: FriendsTreeService,
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

    // ─── FAQ ─────────────────────────────────────────────────────────────────

    data class FaqTextRequest(
        @field:Size(max = 300, message = "Question too long")
        val question: String,
        @field:Size(max = 5000, message = "Answer too long")
        val answer: String,
    )

    data class FaqEntryRequest(
        @field:NotBlank(message = "Category must not be blank")
        val category: String,
        val sortOrder: Int,
        @field:Valid
        val german: FaqTextRequest,
        @field:Valid
        val austrian: FaqTextRequest?,
        @field:Valid
        val english: FaqTextRequest?,
    )

    @GetMapping("/faq")
    fun getFaqEntries(): List<AdminFaqEntryResponse> {
        adminGuard.requireAdmin()
        return faqService.listAll()
    }

    @GetMapping("/faq/categories")
    fun getFaqCategories(): List<String> {
        adminGuard.requireAdmin()
        return FaqCategory.entries.map { it.name }
    }

    @PostMapping("/faq")
    fun createFaqEntry(@Valid @RequestBody request: FaqEntryRequest): AdminFaqEntryResponse {
        adminGuard.requireAdmin()
        return faqService.create(
            category = request.parsedCategory(),
            sortOrder = request.sortOrder,
            german = request.german.toFaqText(),
            austrian = request.austrian?.toFaqText(),
            english = request.english?.toFaqText(),
        )
    }

    @PutMapping("/faq/{id}")
    fun updateFaqEntry(@PathVariable id: String, @Valid @RequestBody request: FaqEntryRequest): AdminFaqEntryResponse {
        adminGuard.requireAdmin()
        require(ValidationUtils.validateObjectId(id)) { "Invalid FAQ entry id" }
        return faqService.update(
            id = ObjectId(id),
            category = request.parsedCategory(),
            sortOrder = request.sortOrder,
            german = request.german.toFaqText(),
            austrian = request.austrian?.toFaqText(),
            english = request.english?.toFaqText(),
        )
    }

    @DeleteMapping("/faq/{id}")
    fun deleteFaqEntry(@PathVariable id: String) {
        adminGuard.requireAdmin()
        require(ValidationUtils.validateObjectId(id)) { "Invalid FAQ entry id" }
        faqService.delete(ObjectId(id))
    }

    private fun FaqEntryRequest.parsedCategory(): FaqCategory =
        FaqCategory.fromId(category)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown category: $category")

    private fun FaqTextRequest.toFaqText(): FaqText = FaqText(question = question, answer = answer)

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
        @RequestParam(defaultValue = "DATE") sort: String,
        @RequestParam(defaultValue = "false") ascending: Boolean,
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

        val parsedSort = runCatching { LogSort.valueOf(sort.uppercase()) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown sort: $sort")
        }

        return loggingService.getLogs(parsedLogType, parsedSort, ascending, page, pageSize)
    }

    @GetMapping("/logs/types")
    fun getLogTypes(): List<String> {
        adminGuard.requireAdmin()
        return LogType.entries.map { it.name }
    }

    // ─── Game scores ─────────────────────────────────────────────────────────

    data class ScoreUpdateRequest(
        val score: Long,
        val timeMillis: Long,
    )

    @GetMapping("/scores")
    fun getScores(
        @RequestParam(required = false) game: String?,
        @RequestParam(required = false) difficulty: String?,
        @RequestParam(required = false) userId: String?,
        @RequestParam(defaultValue = "DATE") sort: String,
        @RequestParam(defaultValue = "false") ascending: Boolean,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") pageSize: Int,
    ): AdminScorePage {
        adminGuard.requireAdmin()
        require(ValidationUtils.validatePaginationPage(page)) { "Invalid page number" }
        require(ValidationUtils.validatePaginationPageSize(pageSize)) { "Invalid page size" }

        val parsedGame = game?.takeIf { it.isNotBlank() }?.let {
            Game.fromId(it) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown game: $it")
        }
        val parsedDifficulty = difficulty?.takeIf { it.isNotBlank() }?.let {
            Difficulty.fromId(it) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown difficulty: $it")
        }
        val parsedUserId = userId?.takeIf { it.isNotBlank() }?.let {
            require(ValidationUtils.validateObjectId(it)) { "Invalid user id" }
            ObjectId(it)
        }
        val parsedSort = runCatching { ScoreSort.valueOf(sort.uppercase()) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown sort: $sort")
        }

        return adminScoreService.getScores(parsedGame, parsedDifficulty, parsedUserId, parsedSort, ascending, page, pageSize)
    }

    @GetMapping("/scores/games")
    fun getGames(): Map<String, List<String>> {
        adminGuard.requireAdmin()
        return mapOf(
            "games" to Game.entries.map { it.name },
            "difficulties" to Difficulty.entries.map { it.name },
        )
    }

    @PutMapping("/scores/{id}")
    fun updateScore(@PathVariable id: String, @RequestBody request: ScoreUpdateRequest): AdminScoreResponse {
        adminGuard.requireAdmin()
        require(ValidationUtils.validateObjectId(id)) { "Invalid score id" }
        return adminScoreService.updateScore(ObjectId(id), request.score, request.timeMillis)
    }

    @DeleteMapping("/scores/{id}")
    fun deleteScore(@PathVariable id: String) {
        adminGuard.requireAdmin()
        require(ValidationUtils.validateObjectId(id)) { "Invalid score id" }
        adminScoreService.deleteScore(ObjectId(id))
    }

    // ─── Users ───────────────────────────────────────────────────────────────

    @GetMapping("/users")
    fun getUsers(): List<AdminUserResponse> {
        adminGuard.requireAdmin()
        return adminUserService.listUsers()
    }

    @PostMapping("/users/{id}/logout")
    fun forceLogout(@PathVariable id: String) {
        adminGuard.requireAdmin()
        require(ValidationUtils.validateObjectId(id)) { "Invalid user id" }
        adminUserService.forceLogout(ObjectId(id))
    }

    // ─── Friends tree ────────────────────────────────────────────────────────

    @GetMapping("/friends-tree")
    fun getFriendsTree(): FriendsTreeResponse {
        adminGuard.requireAdmin()
        return friendsTreeService.buildTree()
    }
}
