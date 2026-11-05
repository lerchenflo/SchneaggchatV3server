package com.lerchenflo.schneaggchatv3server.website.donations.model

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
