package com.lerchenflo.schneaggchatv3server.donations

import com.lerchenflo.schneaggchatv3server.donations.model.DonationsListResponse
import com.lerchenflo.schneaggchatv3server.donations.model.toDonationResponse
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
}
