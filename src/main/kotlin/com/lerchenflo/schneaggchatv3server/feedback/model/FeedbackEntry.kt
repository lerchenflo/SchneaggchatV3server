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
 * immutable after creation; only votes, [status] (admin) and deletion change it. Seeded entries are
 * the exception: their texts follow the seed file (see FeedbackSeedService).
 *
 * Synced to clients by `/feedback/sync` (id/timestamp diff on [updatedAt]). The diff reports every
 * entry the client has but the server no longer shows as deleted, so user-created entries can be
 * hard deleted. Seeded entries are the exception, see [deleted].
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

    /**
     * FEATURE only: marked as a premium feature, shown with a highlight in the client. Picked by the
     * creator on create, for seeds taken from the seed file. Not a [FeedbackTag], so released clients
     * with a closed tag enum still parse the entry.
     */
    val premium: Boolean = false,

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
     * Sync cursor: changes on every write that changes the entry's response - votes, status and
     * comments (count and latest dev comment). Entries stored before this field existed fall back to
     * [createdAt]. Usernames and admin roles are read live, so a rename does not move it.
     */
    val updatedAt: Instant = createdAt,

    /**
     * Last change worth highlighting in the client: creation, a status change, or a comment by
     * someone other than the creator. Votes and deleted comments do not move it.
     */
    val activityAt: Instant = createdAt,

    /**
     * Tombstone for deleted seeded entries only. A seeded entry an admin deleted must stay deleted,
     * but a hard delete would drop its [seedKey] and the seeder would re-insert it on the next start.
     * User-created entries are always hard deleted and never carry this flag.
     */
    val deleted: Boolean = false,

    /** Optimistic locking for concurrent vote/status writes (see withOptimisticRetry). */
    @Version val version: Long? = null,
)
