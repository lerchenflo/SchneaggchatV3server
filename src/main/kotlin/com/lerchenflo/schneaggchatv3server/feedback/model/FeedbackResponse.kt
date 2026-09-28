package com.lerchenflo.schneaggchatv3server.feedback.model

import com.fasterxml.jackson.annotation.JsonProperty

data class FeedbackEntryResponse(
    val id: String,
    val type: FeedbackType,
    val title: String,
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
    val createdAt: Long,
)

data class FeedbackListResponse(
    val viewerIsAdmin: Boolean,
    val entries: List<FeedbackEntryResponse>,
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
