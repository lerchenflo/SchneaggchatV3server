package com.lerchenflo.schneaggchatv3server.feedback.model

import com.fasterxml.jackson.annotation.JsonProperty

data class FeedbackEntryResponse(
    val id: String,
    val type: FeedbackType,
    val title: String,
    val description: String,
    val tags: List<FeedbackTag>,
    val status: FeedbackStatus,
    val upvotes: Int,
    val downvotes: Int,
    val didntKnowCount: Int,
    /** FEATURE only, 0 for bugs. Neutral: not part of [upvotes] or [downvotes]. */
    val notNeededCount: Int,
    /** Never [FeedbackVote.NOT_NEEDED] (released clients cannot parse it), see [isNotNeeded]. */
    val myVote: FeedbackVote,
    /** The viewer's vote is [FeedbackVote.NOT_NEEDED]; [myVote] is [FeedbackVote.NONE] then. */
    @get:JsonProperty("isNotNeeded") val isNotNeeded: Boolean,
    val commentCount: Int,
    val latestDevComment: String?,
    /** The creator's user id, for every viewer; null for seeded entries. The client resolves the name. */
    val creatorId: String?,
    @get:JsonProperty("isOwn") val isOwn: Boolean,
    val canDelete: Boolean,
    /** The viewer is the creator or an admin, and the entry is not seeded. */
    val canEdit: Boolean,
    /** Epoch millis of the last edit, null when never edited. */
    val editedAt: Long?,
    @get:JsonProperty("isSeeded") val isSeeded: Boolean,
    @get:JsonProperty("isPremium") val isPremium: Boolean,
    val createdAt: Long,
    /** Epoch millis. The client sends it back as the IdTimeStamp timestamp on `/feedback/sync`. */
    val updatedAt: Long,
    /** Epoch millis, see [FeedbackEntry.activityAt]. Compare with a stored `serverTime`, not the device clock. */
    val activityAt: Long,
)

data class FeedbackSyncResponse(
    val viewerIsAdmin: Boolean,
    val updatedEntries: List<FeedbackEntryResponse>,
    /** Ids the client sent that are deleted or gone. Only filled on page 0. */
    val deletedEntries: List<String>,
    val moreEntries: Boolean,
    /**
     * Server clock in epoch millis, read before the entries. A client that stores it as "last seen"
     * never misses a later [FeedbackEntryResponse.activityAt] or `createdAt`, even with a wrong
     * device clock.
     */
    val serverTime: Long,
)

data class FeedbackCommentResponse(
    val id: String,
    /** The client resolves the name (its user rows, else `/users/lookup`). */
    val authorId: String,
    val text: String,
    @get:JsonProperty("isDevComment") val isDevComment: Boolean,
    val canDelete: Boolean,
    /** The viewer is the author. Admins edit only their own comments. */
    val canEdit: Boolean,
    val appVersion: String?,
    val platform: String?,
    val createdAt: Long,
    /** Epoch millis of the last edit, null when never edited. */
    val editedAt: Long?,
)

data class FeedbackEntryDetailResponse(
    val entry: FeedbackEntryResponse,
    val description: String,
    val location: String?,
    val appVersion: String?,
    val platform: String?,
    val comments: List<FeedbackCommentResponse>,
)
