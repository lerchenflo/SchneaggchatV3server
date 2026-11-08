package com.lerchenflo.schneaggchatv3server.website.donations.model

/**
 * The server-rendered /donations.html. Amounts are already formatted the way `donations-data.js`
 * shows them. No date: formatting one needs a calendar, and the script adds it.
 */
data class DonationsPage(
    val total: String,
    val donations: List<DonationCard>,
)

data class DonationCard(
    val name: String,
    val amount: String,
    val message: String?,
)
