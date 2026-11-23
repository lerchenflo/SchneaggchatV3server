@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.feedback

import com.lerchenflo.schneaggchatv3server.core.security.ratelimit.RateLimitProperties
import com.lerchenflo.schneaggchatv3server.core.security.ratelimit.RateLimitService
import com.lerchenflo.schneaggchatv3server.core.security.ratelimit.RateLimitTier
import com.lerchenflo.schneaggchatv3server.feedback.model.*
import com.lerchenflo.schneaggchatv3server.repository.FeedbackCommentRepository
import com.lerchenflo.schneaggchatv3server.repository.FeedbackEntryRepository
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.user.usermodel.UserRole
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import com.lerchenflo.schneaggchatv3server.util.withOptimisticRetry
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * In-app feedback board: feature requests and bug reports that users vote and comment on.
 * Clients keep the board locally and catch up through [sync]. There is no socket message and no
 * push notification. Every write that changes an entry's response moves [FeedbackEntry.updatedAt],
 * edits of entries and comments included.
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

    // ─── Reads ────────────────────────────────────────────────────────────────

    /**
     * Id/timestamp diff like `/map/sync`, over all live entries of both types: entries the client
     * lacks or has with an older [FeedbackEntry.updatedAt], newest first, plus (page 0 only) the ids
     * the client has that are gone. Every user may see every entry, so nothing is filtered by viewer.
     */
    fun sync(
        clientEntries: List<UserService.IdTimeStamp>,
        page: Int,
        pageSize: Int,
        viewerId: ObjectId,
    ): FeedbackSyncResponse {
        // Read before the entries: a write that lands in between is newer than serverTime, so the
        // client still highlights it next time instead of counting it as seen.
        val serverTime = Clock.System.now()
        val clientMap = clientEntries.associate { it.id to it.timeStamp }
        val entries = entryRepository.findByDeletedFalse()
        val serverIds = entries.map { it.id.toHexString() }.toSet()

        val changed = entries.filter { entry ->
            // Unknown id or unreadable timestamp: send the entry again.
            val clientTimestamp = clientMap[entry.id.toHexString()]?.toLongOrNull() ?: return@filter true
            entry.updatedAt.toEpochMilliseconds() > clientTimestamp
        }.sortedByDescending { it.updatedAt }

        val start = (page.toLong() * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val viewerIsAdmin = isAdmin(viewerId)

        return FeedbackSyncResponse(
            viewerIsAdmin = viewerIsAdmin,
            updatedEntries = toEntryResponses(changed.drop(start).take(pageSize), viewerId, viewerIsAdmin),
            deletedEntries = if (page == 0) clientMap.keys.filter { it !in serverIds } else emptyList(),
            moreEntries = start.toLong() + pageSize < changed.size,
            serverTime = serverTime.toEpochMilliseconds(),
        )
    }

    fun details(entryId: ObjectId, viewerId: ObjectId): FeedbackEntryDetailResponse {
        val viewerIsAdmin = isAdmin(viewerId)
        val entry = findActiveEntry(entryId)
        val comments = commentRepository.findByEntryIdOrderByCreatedAtAsc(entryId)

        return FeedbackEntryDetailResponse(
            entry = toEntryResponses(listOf(entry), viewerId, viewerIsAdmin).single(),
            description = entry.description,
            location = entry.location,
            appVersion = entry.appVersion,
            platform = entry.platform,
            comments = comments.map { it.toResponse(viewerId, viewerIsAdmin) },
        )
    }

    // ─── Writes ───────────────────────────────────────────────────────────────

    fun create(
        type: FeedbackType,
        title: String,
        description: String?,
        tags: List<FeedbackTag>,
        location: String?,
        premium: Boolean,
        appVersion: String?,
        platform: String?,
        requesterId: ObjectId,
    ): FeedbackEntryResponse {
        val fields = validEntryFields(type, title, description, tags, location)
        val trimmedAppVersion = appVersion?.trim()?.ifEmpty { null }
        val trimmedPlatform = platform?.trim()?.ifEmpty { null }

        require(ValidationUtils.validateFeedbackAppVersion(trimmedAppVersion)) { "App version too long" }
        require(ValidationUtils.validateFeedbackPlatform(trimmedPlatform)) { "Platform too long" }

        requireWithinRateLimit(requesterId, RateLimitTier.FEEDBACK_CREATE)

        val saved = entryRepository.save(
            FeedbackEntry(
                type = type,
                title = fields.title,
                description = fields.description,
                tags = tags,
                status = FeedbackStatus.initialFor(type),
                location = fields.location,
                // Like location, premium only exists on features - silently dropped for bugs.
                premium = premium && type == FeedbackType.FEATURE,
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
            if (vote == FeedbackVote.NOT_NEEDED) {
                require(entry.type == FeedbackType.FEATURE) { "\"Don't need it, but fine\" votes are only allowed on features" }
            }

            val newVotes = if (vote == FeedbackVote.NONE) entry.votes - voterKey else entry.votes + (voterKey to vote)
            if (newVotes == entry.votes) entry
            else entryRepository.save(entry.copy(votes = newVotes, updatedAt = nextStamp(entry)))
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

        val entry = findActiveEntry(entryId)
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
        // A creator answering on their own entry is nothing new for them, so only others' comments
        // count as activity.
        touchEntry(entryId, activity = entry.creatorId != requesterId)
        loggingService.log(requesterId, LogType.FEEDBACK_COMMENT_CREATED, "entry=${entryId.toHexString()}")

        return saved.toResponse(requesterId, authorIsAdmin)
    }

    /**
     * Creator or admin. Seeded entries are read-only for everyone (the seed file would overwrite an
     * edit), so 403; anyone else gets the same 404 as for an unknown id, like [deleteEntry].
     *
     * A type change resets the status to the start status of the new type and drops the votes the
     * new type and status can't hold ("didn't know", and "don't need it" on a bug); up and down votes
     * stay. Premium and location only exist on features, so a switch to a bug clears both and a sent
     * `premium = true` is dropped for a bug, like on create. [premium] null (released clients don't
     * send it) keeps the stored value; a switch back to a feature sets it again only when sent.
     *
     * An edit is no news for others: it moves [FeedbackEntry.updatedAt] for the sync, never
     * [FeedbackEntry.activityAt]. An edit that changes nothing writes nothing and sets no
     * [FeedbackEntry.editedAt].
     */
    fun editEntry(
        entryId: ObjectId,
        type: FeedbackType,
        title: String,
        description: String?,
        tags: List<FeedbackTag>,
        location: String?,
        premium: Boolean?,
        requesterId: ObjectId,
    ): FeedbackEntryResponse {
        val fields = validEntryFields(type, title, description, tags, location)
        val requesterIsAdmin = isAdmin(requesterId)

        val current = findActiveEntry(entryId)
        if (current.seedKey != null) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Seeded entries can't be edited")
        val isOwn = current.creatorId != null && current.creatorId == requesterId
        if (!(isOwn || requesterIsAdmin)) throw entryNotFound()
        requireWithinRateLimit(requesterId, RateLimitTier.FEEDBACK_EDIT)

        var changed = false
        val saved = withOptimisticRetry {
            val entry = findActiveEntry(entryId)
            val typeChanged = type != entry.type
            val edited = entry.copy(
                type = type,
                title = fields.title,
                description = fields.description,
                tags = tags,
                location = fields.location,
                status = if (typeChanged) FeedbackStatus.initialFor(type) else entry.status,
                premium = (premium ?: entry.premium) && type == FeedbackType.FEATURE,
                votes = if (!typeChanged) entry.votes else entry.votes.filterValues {
                    it != FeedbackVote.DIDNT_KNOW && !(it == FeedbackVote.NOT_NEEDED && type == FeedbackType.BUG)
                },
            )
            changed = edited != entry
            if (!changed) entry
            else {
                val stamp = nextStamp(entry)
                entryRepository.save(edited.copy(updatedAt = stamp, editedAt = stamp))
            }
        }
        if (changed) loggingService.log(requesterId, LogType.FEEDBACK_EDITED, "entry=${entryId.toHexString()} type=$type")

        return toEntryResponses(listOf(saved), requesterId, requesterIsAdmin).single()
    }

    /**
     * Author only, admins included (they edit only their own comments); anyone else gets the same
     * 404 as for an unknown id, like [deleteComment]. [FeedbackComment.devComment] keeps its value
     * from the time of writing. Moves the entry's sync cursor (its latest dev comment may change),
     * never its activity. The same text again writes nothing and sets no [FeedbackComment.editedAt].
     */
    fun editComment(commentId: ObjectId, text: String, requesterId: ObjectId): FeedbackCommentResponse {
        val trimmedText = text.trim()
        require(ValidationUtils.validateFeedbackComment(trimmedText)) { "Comment must be 1 to 1000 characters" }

        val comment = commentRepository.findById(commentId).getOrNull()?.takeIf { it.authorId == requesterId }
            ?: throw commentNotFound()
        val requesterIsAdmin = isAdmin(requesterId)
        if (comment.text == trimmedText) return comment.toResponse(requesterId, requesterIsAdmin)
        requireWithinRateLimit(requesterId, RateLimitTier.FEEDBACK_EDIT)

        // Update in place instead of save(): a comment deleted in the meantime must stay deleted.
        val saved = mongoTemplate.findAndModify(
            Query(Criteria.where("_id").`is`(commentId)),
            Update().set("text", trimmedText).set("editedAt", Clock.System.now()),
            FindAndModifyOptions.options().returnNew(true),
            FeedbackComment::class.java,
        ) ?: throw commentNotFound()
        touchEntry(comment.entryId, activity = false)
        loggingService.log(requesterId, LogType.FEEDBACK_COMMENT_EDITED, "comment=${commentId.toHexString()}")

        return saved.toResponse(requesterId, requesterIsAdmin)
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
                entryRepository.save(fresh.copy(deleted = true, votes = emptyMap(), updatedAt = nextStamp(fresh)))
            }
        } else {
            entryRepository.deleteById(entryId)
        }
        loggingService.log(requesterId, LogType.FEEDBACK_DELETED, "entry=${entryId.toHexString()} seedKey=${entry.seedKey}")
    }

    fun deleteComment(commentId: ObjectId, requesterId: ObjectId) {
        val comment = commentRepository.findById(commentId).getOrNull() ?: throw commentNotFound()
        if (comment.authorId != requesterId && !isAdmin(requesterId)) throw commentNotFound()

        commentRepository.deleteById(commentId)
        touchEntry(comment.entryId, activity = false)
        loggingService.log(requesterId, LogType.FEEDBACK_COMMENT_DELETED, "comment=${commentId.toHexString()}")
    }

    /** Admin only - the caller is expected to have passed AdminGuard.requireAdmin(). */
    fun setStatus(entryId: ObjectId, status: FeedbackStatus, adminId: ObjectId): FeedbackEntryResponse {
        val saved = withOptimisticRetry {
            val entry = findActiveEntry(entryId)
            require(status.type == entry.type) { "Status $status is not valid for ${entry.type} entries" }

            // "Didn't know" only means something on an implemented feature - drop those votes once
            // the entry leaves that status, so they cannot resurface if it is set back later.
            // NOT_NEEDED votes stay: they apply to a feature in every status.
            val newVotes = if (status == FeedbackStatus.IMPLEMENTED) entry.votes
            else entry.votes.filterValues { it != FeedbackVote.DIDNT_KNOW }

            val stamp = nextStamp(entry)
            entryRepository.save(
                entry.copy(
                    status = status,
                    votes = newVotes,
                    updatedAt = stamp,
                    // Setting the same status again is no news for the creator.
                    activityAt = if (status != entry.status) stamp else entry.activityAt,
                )
            )
        }
        loggingService.log(adminId, LogType.FEEDBACK_STATUS_CHANGED, "entry=${entryId.toHexString()} status=$status")

        return toEntryResponses(listOf(saved), adminId, viewerIsAdmin = true).single()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun findActiveEntry(entryId: ObjectId): FeedbackEntry =
        entryRepository.findById(entryId).getOrNull()?.takeIf { !it.deleted } ?: throw entryNotFound()

    private fun entryNotFound() = ResponseStatusException(HttpStatus.NOT_FOUND, "Feedback entry not found")

    private fun commentNotFound() = ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found")

    /** Trimmed entry texts that passed the create and edit checks. */
    private data class EntryFields(val title: String, val description: String, val location: String?)

    private fun validEntryFields(
        type: FeedbackType,
        title: String,
        description: String?,
        tags: List<FeedbackTag>,
        location: String?,
    ): EntryFields {
        val trimmedTitle = title.trim()
        // Optional: no description is stored as "", so responses keep a non-null string.
        val trimmedDescription = description.orEmpty().trim()
        // Location only makes sense for features ("where to find it") - silently dropped for bugs.
        val trimmedLocation = if (type == FeedbackType.FEATURE) location?.trim()?.ifEmpty { null } else null

        require(ValidationUtils.validateFeedbackTitle(trimmedTitle)) { "Title must be 3 to 100 characters" }
        require(ValidationUtils.validateFeedbackDescription(trimmedDescription)) { "Description must be at most 2000 characters" }
        require(ValidationUtils.validateFeedbackTags(tags.size, tags.distinct().size)) { "Pick 1 to 3 distinct tags" }
        require(ValidationUtils.validateFeedbackLocation(trimmedLocation)) { "Location too long" }
        return EntryFields(trimmedTitle, trimmedDescription, trimmedLocation)
    }

    /**
     * Next [FeedbackEntry.updatedAt] for [entry]: now, but at least 1 ms after the current value. Two
     * writes in the same millisecond would otherwise share a timestamp, and a client that synced
     * between them would never get the second one.
     */
    private fun nextStamp(entry: FeedbackEntry): Instant =
        maxOf(Clock.System.now(), entry.updatedAt + 1.milliseconds)

    /**
     * Moves the sync cursor (and with [activity] also [FeedbackEntry.activityAt]) of an entry whose
     * response changed while the entry document did not: comment count, latest dev comment. Runs after
     * the comment write, so a client that gets the new timestamp also gets the comment. Skips a
     * deleted or missing entry.
     */
    private fun touchEntry(entryId: ObjectId, activity: Boolean) {
        withOptimisticRetry {
            val entry = entryRepository.findById(entryId).getOrNull()?.takeIf { !it.deleted } ?: return
            val stamp = nextStamp(entry)
            entryRepository.save(entry.copy(updatedAt = stamp, activityAt = if (activity) stamp else entry.activityAt))
        }
    }

    private fun isAdmin(userId: ObjectId): Boolean =
        userLookupService.findById(userId)?.role == UserRole.ADMIN

    /**
     * Builds responses for a batch of entries with a constant number of queries: one aggregation for
     * the comment counts, one find for the dev comments. Users appear only by id (creator, comment
     * authors); the client resolves the names.
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
        val viewerKey = viewerId.toHexString()

        return entries.map { entry ->
            val countDidntKnow = entry.type == FeedbackType.FEATURE && entry.status == FeedbackStatus.IMPLEMENTED
            val isOwn = entry.creatorId != null && entry.creatorId == viewerId
            val isFeature = entry.type == FeedbackType.FEATURE
            val storedVote = entry.votes[viewerKey]
            val isNotNeeded = isFeature && storedVote == FeedbackVote.NOT_NEEDED
            // NOT_NEEDED goes out as NONE + isNotNeeded, see FeedbackVote.
            val myVote = storedVote
                ?.takeIf { it != FeedbackVote.DIDNT_KNOW || countDidntKnow }
                ?.takeIf { it != FeedbackVote.NOT_NEEDED }
                ?: FeedbackVote.NONE

            FeedbackEntryResponse(
                id = entry.id.toHexString(),
                type = entry.type,
                title = entry.title,
                description = entry.description,
                tags = entry.tags,
                status = entry.status,
                upvotes = entry.votes.values.count { it == FeedbackVote.UP },
                downvotes = entry.votes.values.count { it == FeedbackVote.DOWN },
                didntKnowCount = if (countDidntKnow) entry.votes.values.count { it == FeedbackVote.DIDNT_KNOW } else 0,
                notNeededCount = if (isFeature) entry.votes.values.count { it == FeedbackVote.NOT_NEEDED } else 0,
                myVote = myVote,
                isNotNeeded = isNotNeeded,
                commentCount = commentCounts[entry.id] ?: 0,
                latestDevComment = latestDevComments[entry.id],
                creatorId = entry.creatorId?.toHexString(),
                isOwn = isOwn,
                canDelete = isOwn || viewerIsAdmin,
                canEdit = (isOwn || viewerIsAdmin) && entry.seedKey == null,
                editedAt = entry.editedAt?.toEpochMilliseconds(),
                isSeeded = entry.seedKey != null,
                seedKey = entry.seedKey,
                isPremium = entry.premium,
                createdAt = entry.createdAt.toEpochMilliseconds(),
                updatedAt = entry.updatedAt.toEpochMilliseconds(),
                activityAt = entry.activityAt.toEpochMilliseconds(),
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

    private fun FeedbackComment.toResponse(
        viewerId: ObjectId,
        viewerIsAdmin: Boolean,
    ) = FeedbackCommentResponse(
        id = id.toHexString(),
        authorId = authorId.toHexString(),
        text = text,
        isDevComment = devComment,
        canDelete = authorId == viewerId || viewerIsAdmin,
        canEdit = authorId == viewerId,
        appVersion = appVersion,
        platform = platform,
        createdAt = createdAt.toEpochMilliseconds(),
        editedAt = editedAt?.toEpochMilliseconds(),
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
            AppLogger.warn("RATELIMIT ${tier.name} unavailable, failing open: ${e.message}", user = userId, tag = "check_feedback_rate_limit_unavailable")
            return
        }

        if (!probe.isConsumed) {
            val limit = rateLimitProperties.tierConfig(tier)
            AppLogger.warn(
                "RATELIMIT ${tier.name} exceeded (more than ${limit.capacity} per ${limit.refillPeriod}), " +
                        "429",
                user = userId,
                tag = "exceed_feedback_rate_limit",
            )
            throw ResponseStatusException(HttpStatusCode.valueOf(429), "Too many requests. Please try again later.")
        }
    }
}
