package com.lerchenflo.schneaggchatv3server.website.donations.model

/** Public shape - served on the donations page. Never carries `deleted`. */
data class DonationResponse(
    val id: String,
    val name: String,
    val amountCents: Long,
    val donatedAt: Long,
    val message: String?,
)

data class DonationsListResponse(
    val donations: List<DonationResponse>,
    val totalCents: Long,
)

/** Admin shape - includes soft-deleted rows so they can be restored, and audit timestamps. */
data class AdminDonationResponse(
    val id: String,
    val name: String,
    val amountCents: Long,
    val donatedAt: Long,
    val message: String?,
    val deleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
