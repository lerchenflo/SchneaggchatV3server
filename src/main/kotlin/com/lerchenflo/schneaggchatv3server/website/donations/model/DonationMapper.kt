package com.lerchenflo.schneaggchatv3server.website.donations.model

fun Donation.toDonationResponse(): DonationResponse = DonationResponse(
    id = id.toHexString(),
    name = name,
    amountCents = amountCents,
    donatedAt = donatedAt.toEpochMilliseconds(),
    message = message,
)

/** Same text as `€${amount.toFixed(2)}` in `donations-data.js`, for example €5.00. */
fun formatEuro(cents: Long): String = "€${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

fun Donation.toDonationCard(): DonationCard = DonationCard(
    name = name,
    amount = formatEuro(amountCents),
    // An empty message gets no line, like in donations-data.js
    message = message?.takeIf { it.isNotEmpty() }?.let { "\"$it\"" },
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
