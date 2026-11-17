@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.website.faq

import com.lerchenflo.schneaggchatv3server.repository.FaqRepository
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.Json
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import com.lerchenflo.schneaggchatv3server.website.faq.model.FaqCategory
import com.lerchenflo.schneaggchatv3server.website.faq.model.FaqEntry
import com.lerchenflo.schneaggchatv3server.website.faq.model.FaqText
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Inserts the FAQ entries from `seed/faq_entries.json`, so a fresh database is never an empty FAQ
 * page.
 *
 * Runs on every startup and inserts only the seeds whose [FaqEntry.seedKey] is missing from the
 * collection, soft-deleted rows included - an entry an admin deleted or rewrote stays that way.
 * A changed seed text therefore only reaches fresh databases; live rows are edited in the admin
 * panel. The Vorarlbergerisch (`austrian`) texts are checked by hand.
 */
@Service
class FaqSeedService(
    private val faqRepository: FaqRepository,
) {

    internal data class FaqSeedText(
        val question: String = "",
        val answer: String = "",
    )

    internal data class FaqSeed(
        val seedKey: String = "",
        /** A [FaqCategory] name. */
        val category: String = "",
        val sortOrder: Int = 0,
        val german: FaqSeedText? = null,
        val english: FaqSeedText? = null,
        val austrian: FaqSeedText? = null,
    )

    fun seedMissingEntries() {
        val resource = ClassPathResource("seed/faq_entries.json")
        if (!resource.exists()) {
            AppLogger.warn("FAQ seed file not found, skipping FAQ seeding", tag = "seed_faq_file_missing")
            return
        }

        val seeds = try {
            Json.mapper.readValue(resource.inputStream, object : TypeReference<List<FaqSeed>>() {})
        } catch (e: Exception) {
            AppLogger.error("Could not parse FAQ seed file: ${e.message}", tag = "seed_faq_parse_failed")
            return
        }

        val existingKeys = faqRepository.findAll().mapNotNull { it.seedKey }.toSet()
        val now = Clock.System.now()
        val missing = seeds
            .filter { it.seedKey.isNotBlank() && it.seedKey !in existingKeys }
            .distinctBy { it.seedKey }
            .mapNotNull { seed ->
                val category = FaqCategory.fromId(seed.category)
                val german = seed.german?.toTextOrNull()
                if (category == null || german == null || !ValidationUtils.validateFaqSortOrder(seed.sortOrder)) {
                    AppLogger.warn("Skipping invalid FAQ seed '${seed.seedKey}'", tag = "seed_faq_invalid_entry")
                    return@mapNotNull null
                }
                FaqEntry(
                    category = category,
                    sortOrder = seed.sortOrder,
                    seedKey = seed.seedKey,
                    german = german,
                    english = seed.english?.toTextOrNull(),
                    austrian = seed.austrian?.toTextOrNull(),
                    createdAt = now,
                    updatedAt = now,
                )
            }
        if (missing.isEmpty()) return

        faqRepository.saveAll(missing)
        AppLogger.info("Seeded ${missing.size} FAQ entries")
    }

    /** Null when question or answer is missing or too long; a missing translation falls back to German on the page. */
    private fun FaqSeedText.toTextOrNull(): FaqText? {
        val q = question.trim()
        val a = answer.trim()
        if (!ValidationUtils.validateFaqQuestion(q) || !ValidationUtils.validateFaqAnswer(a)) return null
        return FaqText(q, a)
    }
}
