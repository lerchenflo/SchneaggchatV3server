@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.feedback.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Document("feedback_comments")
@TypeAlias("feedbackcomment")
data class FeedbackComment(
    @Id val id: ObjectId = ObjectId(),

    @Indexed val entryId: ObjectId,
    val authorId: ObjectId,
    val text: String,

    /** True when the author was an admin at write time. Set server-side, never by the client. */
    val devComment: Boolean,

    val appVersion: String? = null,
    val platform: String? = null,

    val createdAt: Instant,
)
