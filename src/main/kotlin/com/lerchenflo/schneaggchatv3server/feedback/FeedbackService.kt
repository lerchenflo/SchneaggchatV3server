@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.feedback

import com.lerchenflo.schneaggchatv3server.core.security.ratelimit.RateLimitProperties
import com.lerchenflo.schneaggchatv3server.core.security.ratelimit.RateLimitService
import com.lerchenflo.schneaggchatv3server.core.security.ratelimit.RateLimitTier
import com.lerchenflo.schneaggchatv3server.feedback.model.*
import com.lerchenflo.schneaggchatv3server.repository.FeedbackCommentRepository
import com.lerchenflo.schneaggchatv3server.repository.FeedbackEntryRepository
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.usermodel.UserRole
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import com.lerchenflo.schneaggchatv3server.util.withOptimisticRetry
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * In-app feedback board: feature requests and bug reports that users vote and comment on.
 * Clients fetch the board live - there is no sync endpoint and no push notification.
 */
@Service
class FeedbackService(
    private val entryRepository: FeedbackEntryRepository,
    private val commentRepository: FeedbackCommentRepository,
    private val userLookupService: UserLookupService,
    private val loggingService: LoggingService,
    private val rateLimitService: RateLimitService,
    private val rateLimitProperties: RateLimitProperties,
    private val mongoTemplate: MongoTemplate,
) {

    private companion object {
        const val DESCRIPTION_PREVIEW_LENGTH = 200
    }

    // ─── Reads ────────────────────────────────────────────────────────────────

    fun list(type: FeedbackType, viewerId: ObjectId): FeedbackListResponse {
        val viewerIsAdmin = isAdmin(viewerId)
        val entries = entryRepository.findByTypeAndDeletedFalse(type)
        return FeedbackListResponse(
            viewerIsAdmin = viewerIsAdmin,
            entries = toEntryResponses(entries, viewerId, viewerIsAdmin),
        )
    }

    fun details(entryId: ObjectId, viewerId: ObjectId): FeedbackEntryDetailResponse {
        val viewerIsAdmin = isAdmin(viewerId)
        val entry = findActiveEntry(entryId)
        val comments = commentRepository.findByEntryIdOrderByCreatedAtAsc(entryId)

        val authorNames = usernamesById(comments.map { it.authorId })

        return FeedbackEntryDetailResponse(
            entry = toEntryResponses(listOf(entry), viewerId, viewerIsAdmin).single(),
            description = entry.description,
            location = entry.location,
            appVersion = entry.appVersion,
            platform = entry.platform,
            comments = comments.map { it.toResponse(authorNames[it.authorId], viewerId, viewerIsAdmin) },
        )
    }

    // ─── Writes ───────────────────────────────────────────────────────────────

    fun create(
        type: FeedbackType,
        title: String,
        description: String,
        tags: List<FeedbackTag>,
        location: String?,
        appVersion: String?,
        platform: String?,
        requesterId: ObjectId,
    ): FeedbackEntryResponse {
        val trimmedTitle = title.trim()
        val trimmedDescription = description.trim()
        // Location only makes sense for features ("where to find it") - silently dropped for bugs.
        val trimmedLocation = if (type == FeedbackType.FEATURE) location?.trim()?.ifEmpty { null } else null
        val trimmedAppVersion = appVersion?.trim()?.ifEmpty { null }
        val trimmedPlatform = platform?.trim()?.ifEmpty { null }

        require(ValidationUtils.validateFeedbackTitle(trimmedTitle)) { "Title must be 3 to 100 characters" }
        require(ValidationUtils.validateFeedbackDescription(trimmedDescription)) { "Description must be 1 to 2000 characters" }
        require(ValidationUtils.validateFeedbackTags(tags.size, tags.distinct().size)) { "Pick 1 to 3 distinct tags" }
        require(ValidationUtils.validateFeedbackLocation(trimmedLocation)) { "Location too long" }
        require(ValidationUtils.validateFeedbackAppVersion(trimmedAppVersion)) { "App version too long" }
        require(ValidationUtils.validateFeedbackPlatform(trimmedPlatform)) { "Platform too long" }

        requireWithinRateLimit(requesterId, RateLimitTier.FEEDBACK_CREATE)

        val saved = entryRepository.save(
            FeedbackEntry(
                type = type,
                title = trimmedTitle,
                description = trimmedDescription,
                tags = tags,
                status = FeedbackStatus.initialFor(type),
                location = trimmedLocation,
                creatorId = requesterId,
                appVersion = trimmedAppVersion,
                platform = trimmedPlatform,
                createdAt = Clock.System.now(),
            )
        )
        loggingService.log(requesterId, LogType.FEEDBACK_CREATED, "entry=${saved.id.toHexString()} type=$type")

        return toEntryResponses(listOf(saved), requesterId, isAdmin(requesterId)).single()
    }

    fun vote(entryId: ObjectId, vote: FeedbackVote, requesterId: ObjectId): FeedbackEntryResponse {
        requireWithinRateLimit(requesterId, RateLimitTier.FEEDBACK_VOTE)

        val voterKey = requesterId.toHexString()
        val saved = withOptimisticRetry {
            val entry = findActiveEntry(entryId)

            if (vote == FeedbackVote.DIDNT_KNOW) {
                require(entry.type == FeedbackType.FEATURE && entry.status == FeedbackStatus.IMPLEMENTED) {
                    "\"Didn't know\" votes are only allowed on implemented features"
                }
            }

            val newVotes = if (vote == FeedbackVote.NONE) entry.votes - voterKey else entry.votes + (voterKey to vote)
            if (newVotes == entry.votes) entry else entryRepository.save(entry.copy(votes = newVotes))
        }

        return toEntryResponses(listOf(saved), requesterId, isAdmin(requesterId)).single()
    }

    fun comment(
        entryId: ObjectId,
        text: String,
        appVersion: String?,
        platform: String?,
        requesterId: ObjectId,
    ): FeedbackCommentResponse {
        val trimmedText = text.trim()
        val trimmedAppVersion = appVersion?.trim()?.ifEmpty { null }
        val trimmedPlatform = platform?.trim()?.ifEmpty { null }

        require(ValidationUtils.validateFeedbackComment(trimmedText)) { "Comment must be 1 to 1000 characters" }
        require(ValidationUtils.validateFeedbackAppVersion(trimmedAppVersion)) { "App version too long" }
        require(ValidationUtils.validateFeedbackPlatform(trimmedPlatform)) { "Platform too long" }

        findActiveEntry(entryId)
        requireWithinRateLimit(requesterId, RateLimitTier.FEEDBACK_COMMENT)

        val author = userLookupService.findById(requesterId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        val authorIsAdmin = author.role == UserRole.ADMIN

        val saved = commentRepository.save(
            FeedbackComment(
                entryId = entryId,
                authorId = requesterId,
                text = trimmedText,
                devComment = authorIsAdmin,
                appVersion = trimmedAppVersion,
                platform = trimmedPlatform,
                createdAt = Clock.System.now(),
            )
        )
        loggingService.log(requesterId, LogType.FEEDBACK_COMMENT_CREATED, "entry=${entryId.toHexString()}")

        return saved.toResponse(author.username, requesterId, authorIsAdmin)
    }

    /**
     * Creator or admin only; seeded entries are admin only. Everyone else gets the same 404 as for an
     * unknown id so the endpoint does not reveal which ids exist.
     */
    fun deleteEntry(entryId: ObjectId, requesterId: ObjectId) {
        val entry = findActiveEntry(entryId)
        val requesterIsAdmin = isAdmin(requesterId)
        val isOwn = entry.creatorId != null && entry.creatorId == requesterId
        if (!(isOwn || requesterIsAdmin)) throw entryNotFound()

        commentRepository.deleteByEntryId(entryId)
        if (entry.seedKey != null) {
            // Tombstone instead of hard delete, so the seeder does not re-insert it on the next start.
            withOptimisticRetry {
                val fresh = findActiveEntry(entryId)
                entryRepository.save(fresh.copy(deleted = true, votes = emptyMap()))
            }
        } else {
            entryRepository.deleteById(entryId)
        }
        loggingService.log(requesterId, LogType.FEEDBACK_DELETED, "entry=${entryId.toHexString()} seedKey=${entry.seedKey}")
    }

    fun deleteComment(commentId: ObjectId, requesterId: ObjectId) {
        val comment = commentRepository.findById(commentId).getOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found")
        if (comment.authorId != requesterId && !isAdmin(requesterId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found")
        }

        commentRepository.deleteById(commentId)
        loggingService.log(requesterId, LogType.FEEDBACK_COMMENT_DELETED, "comment=${commentId.toHexString()}")
    }

    /** Admin only - the caller is expected to have passed AdminGuard.requireAdmin(). */
    fun setStatus(entryId: ObjectId, status: FeedbackStatus, adminId: ObjectId): FeedbackEntryResponse {
        val saved = withOptimisticRetry {
            val entry = findActiveEntry(entryId)
            require(status.type == entry.type) { "Status $status is not valid for ${entry.type} entries" }

            // "Didn't know" only means something on an implemented feature - drop those votes once
            // the entry leaves that status, so they cannot resurface if it is set back later.
            val newVotes = if (status == FeedbackStatus.IMPLEMENTED) entry.votes
            else entry.votes.filterValues { it != FeedbackVote.DIDNT_KNOW }

            entryRepository.save(entry.copy(status = status, votes = newVotes))
        }
        loggingService.log(adminId, LogType.FEEDBACK_STATUS_CHANGED, "entry=${entryId.toHexString()} status=$status")

        return toEntryResponses(listOf(saved), adminId, viewerIsAdmin = true).single()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun findActiveEntry(entryId: ObjectId): FeedbackEntry =
        entryRepository.findById(entryId).getOrNull()?.takeIf { !it.deleted } ?: throw entryNotFound()

    private fun entryNotFound() = ResponseStatusException(HttpStatus.NOT_FOUND, "Feedback entry not found")

    private fun isAdmin(userId: ObjectId): Boolean =
        userLookupService.findById(userId)?.role == UserRole.ADMIN

    private fun usernamesById(ids: Collection<ObjectId>): Map<ObjectId, String> {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return userLookupService.findAllById(distinct).associate { it.id to it.username }
    }

    /**
     * Builds responses for a batch of entries with a constant number of queries: one aggregation for
     * the comment counts, one find for the dev comments, one user lookup for creator names.
     */
    private fun toEntryResponses(
        entries: List<FeedbackEntry>,
        viewerId: ObjectId,
        viewerIsAdmin: Boolean,
    ): List<FeedbackEntryResponse> {
        if (entries.isEmpty()) return emptyList()
        val entryIds = entries.map { it.id }

        val commentCounts = countCommentsByEntry(entryIds)
        val latestDevComments = commentRepository.findByEntryIdInAndDevCommentTrue(entryIds)
            .groupBy { it.entryId }
            .mapValues { (_, comments) -> comments.maxBy { it.createdAt }.text }
        val creatorNames = usernamesById(entries.mapNotNull { it.creatorId })
        val viewerKey = viewerId.toHexString()

        return entries.map { entry ->
            val countDidntKnow = entry.type == FeedbackType.FEATURE && entry.status == FeedbackStatus.IMPLEMENTED
            val isOwn = entry.creatorId != null && entry.creatorId == viewerId
            val myVote = entry.votes[viewerKey]
                ?.takeIf { it != FeedbackVote.DIDNT_KNOW || countDidntKnow }
                ?: FeedbackVote.NONE

            FeedbackEntryResponse(
                id = entry.id.toHexString(),
                type = entry.type,
                title = entry.title,
                descriptionPreview = previewOf(entry.description),
                tags = entry.tags,
                status = entry.status,
                upvotes = entry.votes.values.count { it == FeedbackVote.UP },
                downvotes = entry.votes.values.count { it == FeedbackVote.DOWN },
                didntKnowCount = if (countDidntKnow) entry.votes.values.count { it == FeedbackVote.DIDNT_KNOW } else 0,
                myVote = myVote,
                commentCount = commentCounts[entry.id] ?: 0,
                latestDevComment = latestDevComments[entry.id],
                creatorName = entry.creatorId?.let { creatorNames[it] },
                isOwn = isOwn,
                canDelete = isOwn || viewerIsAdmin,
                isSeeded = entry.seedKey != null,
                createdAt = entry.createdAt.toEpochMilliseconds(),
            )
        }
    }

    private data class EntryCommentCount(val id: ObjectId, val count: Int)

    private fun countCommentsByEntry(entryIds: List<ObjectId>): Map<ObjectId, Int> {
        val aggregation = Aggregation.newAggregation(
            Aggregation.match(Criteria.where("entryId").`in`(entryIds)),
            Aggregation.group("entryId").count().`as`("count"),
        )
        return mongoTemplate.aggregate(aggregation, "feedback_comments", EntryCommentCount::class.java)
            .mappedResults
            .associate { it.id to it.count }
    }

    private fun previewOf(description: String): String =
        if (description.length <= DESCRIPTION_PREVIEW_LENGTH) description
        else description.take(DESCRIPTION_PREVIEW_LENGTH) + "…"

    private fun FeedbackComment.toResponse(
        authorName: String?,
        viewerId: ObjectId,
        viewerIsAdmin: Boolean,
    ) = FeedbackCommentResponse(
        id = id.toHexString(),
        authorId = authorId.toHexString(),
        authorName = authorName,
        text = text,
        isDevComment = devComment,
        canDelete = authorId == viewerId || viewerIsAdmin,
        appVersion = appVersion,
        platform = platform,
        createdAt = createdAt.toEpochMilliseconds(),
    )

    /**
     * Per-user action limit on top of the global IP/USER tiers in RateLimitFilter. Fails open when
     * Redis is unavailable, matching the filter's behaviour for non-auth paths.
     */
    private fun requireWithinRateLimit(userId: ObjectId, tier: RateLimitTier) {
        if (!rateLimitProperties.enabled) return

        val key = "rl:${tier.name.lowercase()}:${userId.toHexString()}"
        val probe = try {
            rateLimitService.tryConsume(key, tier)
        } catch (e: Exception) {
            AppLogger.warn("RATELIMIT ${tier.name} unavailable, failing open (user=${userId.toHexString()}): ${e.message}")
            return
        }

        if (!probe.isConsumed) {
            val limit = rateLimitProperties.tierConfig(tier)
            AppLogger.warn(
                "RATELIMIT ${tier.name} exceeded (more than ${limit.capacity} per ${limit.refillPeriod}), " +
                        "429 | user=${userId.toHexString()}"
            )
            throw ResponseStatusException(HttpStatusCode.valueOf(429), "Too many requests. Please try again later.")
        }
    }
}
