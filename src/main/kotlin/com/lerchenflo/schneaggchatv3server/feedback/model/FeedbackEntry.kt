@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.feedback.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * One feature request or bug report on the in-app feedback board. Title, description and tags are
 * immutable after creation; only votes, [status] (admin) and deletion change it.
 *
 * Not synced to clients (they fetch the board live), so user-created entries are hard deleted.
 * Seeded entries are the exception, see [deleted].
 */
@Document("feedback_entries")
@TypeAlias("feedbackentry")
data class FeedbackEntry(
    @Id val id: ObjectId = ObjectId(),

    @Indexed val type: FeedbackType,
    val title: String,
    val description: String,
    val tags: List<FeedbackTag>,
    val status: FeedbackStatus,

    /** FEATURE only: where the feature can be found in the app. */
    val location: String? = null,

    /** Null for seeded entries. */
    val creatorId: ObjectId? = null,

    /**
     * Set only on entries inserted by [com.lerchenflo.schneaggchatv3server.feedback.FeedbackSeedService].
     * Identifies a seed across restarts so it is never inserted twice.
     */
    @Indexed(unique = true, sparse = true)
    val seedKey: String? = null,

    val appVersion: String? = null,
    val platform: String? = null,

    /** userId hex -> vote. Never contains [FeedbackVote.NONE]. */
    val votes: Map<String, FeedbackVote> = emptyMap(),

    val createdAt: Instant,

    /**
     * Tombstone for deleted seeded entries only. A seeded entry an admin deleted must stay deleted,
     * but a hard delete would drop its [seedKey] and the seeder would re-insert it on the next start.
     * User-created entries are always hard deleted and never carry this flag.
     */
    val deleted: Boolean = false,

    /** Optimistic locking for concurrent vote/status writes (see withOptimisticRetry). */
    @Version val version: Long? = null,
)
