package com.lerchenflo.schneaggchatv3server.website.faq

import com.lerchenflo.schneaggchatv3server.website.faq.model.FaqEntry
import com.lerchenflo.schneaggchatv3server.website.faq.model.FaqListResponse
import com.lerchenflo.schneaggchatv3server.website.faq.model.toFaqEntryResponse
import com.lerchenflo.schneaggchatv3server.repository.FaqRepository
import org.springframework.stereotype.Service

/** Public read path for the FAQ page - never returns soft-deleted rows. */
@Service
class FaqLookupService(
    private val faqRepository: FaqRepository,
) {
    fun listPublic(): FaqListResponse {
        return FaqListResponse(
            entries = faqRepository.findByDeletedFalse()
                .sortedWith(faqDisplayOrder)
                .map { it.toFaqEntryResponse() },
        )
    }
}

/**
 * Sorted here rather than in the query: Mongo would order the category by its stored string,
 * while the page shows the sections in the order [com.lerchenflo.schneaggchatv3server.website.faq.model.FaqCategory] declares them.
 */
val faqDisplayOrder: Comparator<FaqEntry> = compareBy({ it.category.ordinal }, { it.sortOrder })
