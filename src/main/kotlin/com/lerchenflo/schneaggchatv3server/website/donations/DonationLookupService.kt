package com.lerchenflo.schneaggchatv3server.website.donations

import com.lerchenflo.schneaggchatv3server.website.donations.model.DonationsListResponse
import com.lerchenflo.schneaggchatv3server.website.donations.model.DonationsPage
import com.lerchenflo.schneaggchatv3server.website.donations.model.formatEuro
import com.lerchenflo.schneaggchatv3server.website.donations.model.toDonationCard
import com.lerchenflo.schneaggchatv3server.website.donations.model.toDonationResponse
import com.lerchenflo.schneaggchatv3server.repository.DonationRepository
import org.springframework.stereotype.Service

/** Public read path for the donations page - never returns soft-deleted rows. */
@Service
class DonationLookupService(
    private val donationRepository: DonationRepository,
) {
    fun listPublic(): DonationsListResponse {
        val donations = donationRepository.findByDeletedFalseOrderByDonatedAtDesc()
        return DonationsListResponse(
            donations = donations.map { it.toDonationResponse() },
            totalCents = donations.sumOf { it.amountCents },
        )
    }

    /** The same list for the donations page the server renders. */
    fun page(): DonationsPage {
        val donations = donationRepository.findByDeletedFalseOrderByDonatedAtDesc()
        return DonationsPage(
            total = formatEuro(donations.sumOf { it.amountCents }),
            donations = donations.map { it.toDonationCard() },
        )
    }
}
