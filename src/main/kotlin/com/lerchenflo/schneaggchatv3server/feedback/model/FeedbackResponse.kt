package com.lerchenflo.schneaggchatv3server.feedback.model

import com.fasterxml.jackson.annotation.JsonProperty

data class FeedbackEntryResponse(
    val id: String,
    val type: FeedbackType,
    val title: String,
    val description: String,
    /** First 200 characters of [description]. Only old clients still read it. */
    @Deprecated("Old clients only, use description. Remove in the next release.")
    val descriptionPreview: String,
    val tags: List<FeedbackTag>,
    val status: FeedbackStatus,
    val upvotes: Int,
    val downvotes: Int,
    val didntKnowCount: Int,
    val myVote: FeedbackVote,
    val commentCount: Int,
    val latestDevComment: String?,
    val creatorName: String?,
    @get:JsonProperty("isOwn") val isOwn: Boolean,
    val canDelete: Boolean,
    @get:JsonProperty("isSeeded") val isSeeded: Boolean,
    @get:JsonProperty("isPremium") val isPremium: Boolean,
    val createdAt: Long,
    /** Epoch millis. The client sends it back as the IdTimeStamp timestamp on `/feedback/sync`. */
    val updatedAt: Long,
    /** Epoch millis, see [FeedbackEntry.activityAt]. Compare with a stored `serverTime`, not the device clock. */
    val activityAt: Long,
)

data class FeedbackListResponse(
    val viewerIsAdmin: Boolean,
    val entries: List<FeedbackEntryResponse>,
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
    val authorId: String,
    val authorName: String?,
    val text: String,
    @get:JsonProperty("isDevComment") val isDevComment: Boolean,
    val canDelete: Boolean,
    val appVersion: String?,
    val platform: String?,
    val createdAt: Long,
)

data class FeedbackEntryDetailResponse(
    val entry: FeedbackEntryResponse,
    val description: String,
    val location: String?,
    val appVersion: String?,
    val platform: String?,
    val comments: List<FeedbackCommentResponse>,
)
