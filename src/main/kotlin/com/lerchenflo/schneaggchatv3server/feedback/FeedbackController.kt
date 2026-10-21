package com.lerchenflo.schneaggchatv3server.feedback

import com.lerchenflo.schneaggchatv3server.core.security.AdminGuard
import com.lerchenflo.schneaggchatv3server.core.security.requireAuth
import com.lerchenflo.schneaggchatv3server.feedback.model.*
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.*

/**
 * Feedback board: feature requests and bug reports. All endpoints require login; status changes are
 * admin only. Length limits are checked on the trimmed values in [FeedbackService] - the annotation
 * limits here are only a coarse upper bound on the raw request.
 */
@RestController
@RequestMapping("/feedback")
class FeedbackController(
    private val feedbackService: FeedbackService,
    private val adminGuard: AdminGuard,
) {

    data class FeedbackCreateRequest(
        val type: FeedbackType,
        @field:NotBlank(message = "Title must not be blank")
        @field:Size(max = 200, message = "Title too long")
        val title: String,
        @field:NotBlank(message = "Description must not be blank")
        @field:Size(max = 4000, message = "Description too long")
        val description: String,
        @field:Size(min = 1, max = 3, message = "Pick 1 to 3 tags")
        val tags: List<FeedbackTag>,
        @field:Size(max = 400, message = "Location too long")
        val location: String? = null,
        @field:Size(max = 60, message = "App version too long")
        val appVersion: String? = null,
        @field:Size(max = 40, message = "Platform too long")
        val platform: String? = null,
    )

    data class FeedbackVoteRequest(
        @field:NotBlank(message = "Entry id must not be blank")
        val entryId: String,
        val vote: FeedbackVote,
    )

    data class FeedbackCommentRequest(
        @field:NotBlank(message = "Entry id must not be blank")
        val entryId: String,
        @field:NotBlank(message = "Comment must not be blank")
        @field:Size(max = 2000, message = "Comment too long")
        val text: String,
        @field:Size(max = 60, message = "App version too long")
        val appVersion: String? = null,
        @field:Size(max = 40, message = "Platform too long")
        val platform: String? = null,
    )

    data class FeedbackStatusRequest(
        @field:NotBlank(message = "Entry id must not be blank")
        val entryId: String,
        val status: FeedbackStatus,
    )

    @GetMapping("/list")
    fun list(@RequestParam type: String): FeedbackListResponse {
        val requesterId = requireAuth()
        // Parsed by hand: a bad enum in a query param would otherwise surface as a 500.
        val feedbackType = FeedbackType.entries.firstOrNull { it.name == type }
        require(feedbackType != null) { "Invalid feedback type" }
        return feedbackService.list(feedbackType, requesterId)
    }

    @GetMapping("/details")
    fun details(@RequestParam entryid: String): FeedbackEntryDetailResponse {
        val requesterId = requireAuth()
        require(ValidationUtils.validateObjectId(entryid)) { "Invalid entry ID" }
        return feedbackService.details(ObjectId(entryid), requesterId)
    }

    @PostMapping("/create")
    fun create(@Valid @RequestBody request: FeedbackCreateRequest): FeedbackEntryResponse {
        val requesterId = requireAuth()
        return feedbackService.create(
            type = request.type,
            title = request.title,
            description = request.description,
            tags = request.tags,
            location = request.location,
            appVersion = request.appVersion,
            platform = request.platform,
            requesterId = requesterId,
        )
    }

    @PostMapping("/vote")
    fun vote(@Valid @RequestBody request: FeedbackVoteRequest): FeedbackEntryResponse {
        val requesterId = requireAuth()
        require(ValidationUtils.validateObjectId(request.entryId)) { "Invalid entry ID" }
        return feedbackService.vote(ObjectId(request.entryId), request.vote, requesterId)
    }

    @PostMapping("/comment")
    fun comment(@Valid @RequestBody request: FeedbackCommentRequest): FeedbackCommentResponse {
        val requesterId = requireAuth()
        require(ValidationUtils.validateObjectId(request.entryId)) { "Invalid entry ID" }
        return feedbackService.comment(
            entryId = ObjectId(request.entryId),
            text = request.text,
            appVersion = request.appVersion,
            platform = request.platform,
            requesterId = requesterId,
        )
    }

    @DeleteMapping("/delete")
    fun delete(@RequestParam entryid: String) {
        val requesterId = requireAuth()
        require(ValidationUtils.validateObjectId(entryid)) { "Invalid entry ID" }
        feedbackService.deleteEntry(ObjectId(entryid), requesterId)
    }

    @DeleteMapping("/deletecomment")
    fun deleteComment(@RequestParam commentid: String) {
        val requesterId = requireAuth()
        require(ValidationUtils.validateObjectId(commentid)) { "Invalid comment ID" }
        feedbackService.deleteComment(ObjectId(commentid), requesterId)
    }

    @PostMapping("/status")
    fun setStatus(@Valid @RequestBody request: FeedbackStatusRequest): FeedbackEntryResponse {
        requireAuth()
        val adminId = adminGuard.requireAdmin()
        require(ValidationUtils.validateObjectId(request.entryId)) { "Invalid entry ID" }
        return feedbackService.setStatus(ObjectId(request.entryId), request.status, adminId)
    }
}
