@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.donations.model

import kotlin.time.ExperimentalTime

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

fun Donation.toDonationResponse(): DonationResponse = DonationResponse(
    id = id.toHexString(),
    name = name,
    amountCents = amountCents,
    donatedAt = donatedAt.toEpochMilliseconds(),
    message = message,
)

fun Donation.toAdminDonationResponse(): AdminDonationResponse = AdminDonationResponse(
    id = id.toHexString(),
    name = name,
    amountCents = amountCents,
    donatedAt = donatedAt.toEpochMilliseconds(),
    message = message,
    deleted = deleted,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
)
