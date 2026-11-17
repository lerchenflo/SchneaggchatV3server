@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.feedback

import tools.jackson.core.type.TypeReference
import com.lerchenflo.schneaggchatv3server.feedback.model.FeedbackEntry
import com.lerchenflo.schneaggchatv3server.feedback.model.FeedbackStatus
import com.lerchenflo.schneaggchatv3server.feedback.model.FeedbackTag
import com.lerchenflo.schneaggchatv3server.feedback.model.FeedbackType
import com.lerchenflo.schneaggchatv3server.repository.FeedbackCommentRepository
import com.lerchenflo.schneaggchatv3server.repository.FeedbackEntryRepository
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.Json
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import com.lerchenflo.schneaggchatv3server.util.withOptimisticRetry
import org.bson.types.ObjectId
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Keeps the implemented features from `seed/feedback_features.json` on the feedback board, so users
 * can vote on (and discover) existing features.
 *
 * Runs on every startup:
 * - inserts the seeds whose [FeedbackEntry.seedKey] is not in the collection yet,
 * - brings title, description, tags, location and [FeedbackEntry.premium] of seeded entries in line
 *   with the file (the file owns these texts, nobody can edit them in the app),
 * - deletes seeded entries whose seed has `removed: true` (comments and votes go with them).
 *
 * Seeds an admin deleted stay in the collection as `deleted = true` tombstones, so they count as
 * present and are neither re-inserted nor updated.
 */
@Service
class FeedbackSeedService(
    private val entryRepository: FeedbackEntryRepository,
    private val commentRepository: FeedbackCommentRepository,
) {

    internal data class FeedbackSeed(
        val seedKey: String = "",
        val title: String = "",
        val description: String = "",
        val tags: List<String> = emptyList(),
        val location: String? = null,
        val premium: Boolean = false,
        /** The feature is gone from the app: the seeded entry is deleted and never inserted again. */
        val removed: Boolean = false,
    )

    /** The fields of an entry the seed file owns, validated like a user-created entry. */
    private data class SeedContent(
        val title: String,
        val description: String,
        val tags: List<FeedbackTag>,
        val location: String?,
        val premium: Boolean,
    )

    fun seedMissingEntries() {
        val resource = ClassPathResource("seed/feedback_features.json")
        if (!resource.exists()) {
            AppLogger.warn("Feedback seed file not found, skipping feedback seeding", tag = "seed_feedback_file_missing")
            return
        }

        val seeds = try {
            Json.mapper.readValue(resource.inputStream, object : TypeReference<List<FeedbackSeed>>() {})
        } catch (e: Exception) {
            AppLogger.error("Could not parse feedback seed file: ${e.message}", tag = "seed_feedback_parse_failed")
            return
        }.filter { it.seedKey.isNotBlank() }.distinctBy { it.seedKey }

        val existing = entryRepository.findBySeedKeyNotNull()
        val existingKeys = existing.mapNotNull { it.seedKey }.toSet()
        val now = Clock.System.now()

        val missing = seeds
            .filter { !it.removed && it.seedKey !in existingKeys }
            .mapNotNull { seed -> seed.contentOrNull()?.let { seed.toEntry(it, now) } }

        if (missing.isNotEmpty()) {
            entryRepository.saveAll(missing)
            AppLogger.info("Seeded ${missing.size} feedback entries")
        }

        syncSeededEntries(seeds, existing)
    }

    /**
     * Applies text changes and removals from the seed file to seeded entries that are not deleted.
     * Moves [FeedbackEntry.updatedAt] so synced clients fetch the entry again, but not
     * [FeedbackEntry.activityAt]: a reworded seed is no news a client should highlight.
     */
    private fun syncSeededEntries(seeds: List<FeedbackSeed>, existing: List<FeedbackEntry>) {
        val seedsByKey = seeds.associateBy { it.seedKey }
        val contentByKey = seeds.filter { !it.removed }.mapNotNull { seed -> seed.contentOrNull()?.let { seed.seedKey to it } }.toMap()

        var updated = 0
        var removed = 0
        existing.filter { !it.deleted }.forEach { stale ->
            val seed = seedsByKey[stale.seedKey] ?: return@forEach
            if (seed.removed) {
                if (removeEntry(stale.id)) removed++
                return@forEach
            }
            val content = contentByKey[seed.seedKey] ?: return@forEach
            if (stale.matches(content)) return@forEach
            withOptimisticRetry {
                val entry = entryRepository.findById(stale.id).getOrNull() ?: return@withOptimisticRetry
                if (entry.deleted || entry.matches(content)) return@withOptimisticRetry
                entryRepository.save(
                    entry.copy(
                        title = content.title,
                        description = content.description,
                        tags = content.tags,
                        location = content.location,
                        premium = content.premium,
                        updatedAt = nextStamp(entry),
                    )
                )
                updated += 1
            }
        }

        if (updated > 0) AppLogger.info("Updated $updated seeded feedback entries from the seed file")
        if (removed > 0) AppLogger.info("Removed $removed seeded feedback entries marked as removed in the seed file")
    }

    /** Same as an admin delete of a seeded entry: comments gone, tombstone without votes. */
    private fun removeEntry(entryId: ObjectId): Boolean {
        commentRepository.deleteByEntryId(entryId)
        var done = false
        withOptimisticRetry {
            val entry = entryRepository.findById(entryId).getOrNull()?.takeIf { !it.deleted } ?: return@withOptimisticRetry
            entryRepository.save(entry.copy(deleted = true, votes = emptyMap(), updatedAt = nextStamp(entry)))
            done = true
        }
        return done
    }

    private fun FeedbackEntry.matches(content: SeedContent): Boolean =
        title == content.title && description == content.description && tags == content.tags &&
            location == content.location && premium == content.premium

    private fun nextStamp(entry: FeedbackEntry): Instant = maxOf(Clock.System.now(), entry.updatedAt + 1.milliseconds)

    private fun FeedbackSeed.contentOrNull(): SeedContent? {
        val parsedTags = tags.mapNotNull { tag -> FeedbackTag.entries.firstOrNull { it.name == tag } }.distinct()
        val trimmedTitle = title.trim()
        val trimmedDescription = description.trim()
        val trimmedLocation = location?.trim()?.ifEmpty { null }

        val valid = parsedTags.size == tags.size &&
                ValidationUtils.validateFeedbackTags(parsedTags.size, parsedTags.size) &&
                ValidationUtils.validateFeedbackTitle(trimmedTitle) &&
                ValidationUtils.validateFeedbackDescription(trimmedDescription) &&
                ValidationUtils.validateFeedbackLocation(trimmedLocation)
        if (!valid) {
            AppLogger.warn("Skipping invalid feedback seed '$seedKey'", tag = "seed_feedback_invalid_entry")
            return null
        }
        return SeedContent(trimmedTitle, trimmedDescription, parsedTags, trimmedLocation, premium)
    }

    private fun FeedbackSeed.toEntry(content: SeedContent, now: Instant) = FeedbackEntry(
        type = FeedbackType.FEATURE,
        title = content.title,
        description = content.description,
        tags = content.tags,
        status = FeedbackStatus.IMPLEMENTED,
        location = content.location,
        premium = content.premium,
        creatorId = null,
        seedKey = seedKey,
        createdAt = now,
    )
}
