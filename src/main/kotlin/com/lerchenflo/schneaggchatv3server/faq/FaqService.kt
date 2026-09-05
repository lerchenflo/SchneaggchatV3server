@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.faq

import com.lerchenflo.schneaggchatv3server.faq.model.AdminFaqEntryResponse
import com.lerchenflo.schneaggchatv3server.faq.model.FaqCategory
import com.lerchenflo.schneaggchatv3server.faq.model.FaqEntry
import com.lerchenflo.schneaggchatv3server.faq.model.FaqText
import com.lerchenflo.schneaggchatv3server.faq.model.toAdminFaqEntryResponse
import com.lerchenflo.schneaggchatv3server.repository.FaqRepository
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Admin-only read/write path for FAQ entries. Public read path is [FaqLookupService]. */
@Service
class FaqService(
    private val faqRepository: FaqRepository,
) {

    fun listAll(): List<AdminFaqEntryResponse> {
        return faqRepository.findAll()
            .sortedWith(faqDisplayOrder)
            .map { it.toAdminFaqEntryResponse() }
    }

    fun create(
        category: FaqCategory,
        sortOrder: Int,
        german: FaqText,
        austrian: FaqText?,
        english: FaqText?,
    ): AdminFaqEntryResponse {
        require(ValidationUtils.validateFaqSortOrder(sortOrder)) { "Invalid sort order" }

        val now = Clock.System.now()
        val saved = faqRepository.save(
            FaqEntry(
                category = category,
                sortOrder = sortOrder,
                german = validated(german),
                austrian = optionalTranslation(austrian),
                english = optionalTranslation(english),
                createdAt = now,
                updatedAt = now,
            )
        )
        return saved.toAdminFaqEntryResponse()
    }

    fun update(
        id: ObjectId,
        category: FaqCategory,
        sortOrder: Int,
        german: FaqText,
        austrian: FaqText?,
        english: FaqText?,
    ): AdminFaqEntryResponse {
        require(ValidationUtils.validateFaqSortOrder(sortOrder)) { "Invalid sort order" }

        val existing = faqRepository.findById(id).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "FAQ entry not found")

        val saved = faqRepository.save(
            existing.copy(
                category = category,
                sortOrder = sortOrder,
                german = validated(german),
                austrian = optionalTranslation(austrian),
                english = optionalTranslation(english),
                updatedAt = Clock.System.now(),
            )
        )
        return saved.toAdminFaqEntryResponse()
    }

    fun delete(id: ObjectId) {
        val existing = faqRepository.findById(id).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "FAQ entry not found")

        faqRepository.save(
            existing.copy(deleted = true, updatedAt = Clock.System.now())
        )
    }

    /** A translation the admin left completely empty is stored as absent - the page falls back to German. */
    private fun optionalTranslation(text: FaqText?): FaqText? {
        if (text == null) return null
        if (text.question.isBlank() && text.answer.isBlank()) return null
        return validated(text)
    }

    private fun validated(text: FaqText): FaqText {
        require(ValidationUtils.validateFaqQuestion(text.question)) { "Invalid FAQ question" }
        require(ValidationUtils.validateFaqAnswer(text.answer)) { "Invalid FAQ answer" }
        return FaqText(question = text.question.trim(), answer = text.answer.trim())
    }
}
