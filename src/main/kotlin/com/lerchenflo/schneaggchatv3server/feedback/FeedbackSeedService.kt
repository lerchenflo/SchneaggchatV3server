@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.feedback

import com.fasterxml.jackson.core.type.TypeReference
import com.lerchenflo.schneaggchatv3server.feedback.model.FeedbackEntry
import com.lerchenflo.schneaggchatv3server.feedback.model.FeedbackStatus
import com.lerchenflo.schneaggchatv3server.feedback.model.FeedbackTag
import com.lerchenflo.schneaggchatv3server.feedback.model.FeedbackType
import com.lerchenflo.schneaggchatv3server.repository.FeedbackEntryRepository
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.Json
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Inserts the already-implemented features from `seed/feedback_features.json` into the feedback
 * board, so users can vote on (and discover) existing features.
 *
 * Runs on every startup and inserts only seeds whose [FeedbackEntry.seedKey] is not in the
 * collection yet. Existing seeded entries are never modified. Seeds an admin deleted stay in the
 * collection as `deleted = true` tombstones, so they count as present and are not re-inserted.
 */
@Service
class FeedbackSeedService(
    private val entryRepository: FeedbackEntryRepository,
) {

    internal data class FeedbackSeed(
        val seedKey: String = "",
        val title: String = "",
        val description: String = "",
        val tags: List<String> = emptyList(),
        val location: String? = null,
    )

    fun seedMissingEntries() {
        val resource = ClassPathResource("seed/feedback_features.json")
        if (!resource.exists()) {
            AppLogger.warn("Feedback seed file not found, skipping feedback seeding")
            return
        }

        val seeds = try {
            Json.mapper.readValue(resource.inputStream, object : TypeReference<List<FeedbackSeed>>() {})
        } catch (e: Exception) {
            AppLogger.error("Could not parse feedback seed file: ${e.message}")
            return
        }

        val existingKeys = entryRepository.findBySeedKeyNotNull().mapNotNull { it.seedKey }.toSet()
        val now = Clock.System.now()

        val missing = seeds
            .filter { it.seedKey.isNotBlank() && it.seedKey !in existingKeys }
            .distinctBy { it.seedKey }
            .mapNotNull { seed -> seed.toEntryOrNull(now) }

        if (missing.isEmpty()) return

        entryRepository.saveAll(missing)
        AppLogger.info("Seeded ${missing.size} feedback entries")
    }

    private fun FeedbackSeed.toEntryOrNull(now: kotlin.time.Instant): FeedbackEntry? {
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
            AppLogger.warn("Skipping invalid feedback seed '$seedKey'")
            return null
        }

        return FeedbackEntry(
            type = FeedbackType.FEATURE,
            title = trimmedTitle,
            description = trimmedDescription,
            tags = parsedTags,
            status = FeedbackStatus.IMPLEMENTED,
            location = trimmedLocation,
            creatorId = null,
            seedKey = seedKey,
            createdAt = now,
        )
    }
}
