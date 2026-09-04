package com.lerchenflo.schneaggchatv3server.donations

import com.lerchenflo.schneaggchatv3server.donations.model.DonationsListResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class DonationController(
    private val donationLookupService: DonationLookupService,
) {

    /** Public - powers the /donations.html page. No auth: donation totals are not sensitive. */
    @GetMapping("/public/donations")
    fun listDonations(): DonationsListResponse {
        return donationLookupService.listPublic()
    }
}
