@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.donations

import com.lerchenflo.schneaggchatv3server.donations.model.AdminDonationResponse
import com.lerchenflo.schneaggchatv3server.donations.model.Donation
import com.lerchenflo.schneaggchatv3server.donations.model.toAdminDonationResponse
import com.lerchenflo.schneaggchatv3server.repository.DonationRepository
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Admin-only read/write path for donations. Public read path is [DonationLookupService]. */
@Service
class DonationService(
    private val donationRepository: DonationRepository,
) {

    fun listAll(): List<AdminDonationResponse> {
        return donationRepository.findAllByOrderByDonatedAtDesc().map { it.toAdminDonationResponse() }
    }

    fun create(name: String, amountCents: Long, donatedAt: Instant, message: String?): AdminDonationResponse {
        validate(name, amountCents, donatedAt, message)

        val now = Clock.System.now()
        val saved = donationRepository.save(
            Donation(
                name = name.trim(),
                amountCents = amountCents,
                donatedAt = donatedAt,
                message = message?.trim()?.ifBlank { null },
                createdAt = now,
                updatedAt = now,
            )
        )
        return saved.toAdminDonationResponse()
    }

    fun update(id: ObjectId, name: String, amountCents: Long, donatedAt: Instant, message: String?): AdminDonationResponse {
        validate(name, amountCents, donatedAt, message)

        val existing = donationRepository.findById(id).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Donation not found")

        val saved = donationRepository.save(
            existing.copy(
                name = name.trim(),
                amountCents = amountCents,
                donatedAt = donatedAt,
                message = message?.trim()?.ifBlank { null },
                updatedAt = Clock.System.now(),
            )
        )
        return saved.toAdminDonationResponse()
    }

    fun delete(id: ObjectId) {
        val existing = donationRepository.findById(id).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Donation not found")

        donationRepository.save(
            existing.copy(deleted = true, updatedAt = Clock.System.now())
        )
    }

    private fun validate(name: String, amountCents: Long, donatedAt: Instant, message: String?) {
        require(ValidationUtils.validateDonationName(name)) { "Invalid donation name" }
        require(ValidationUtils.validateDonationAmount(amountCents)) { "Invalid donation amount" }
        require(ValidationUtils.validateDonationMessage(message)) { "Donation message too long" }
        require(donatedAt <= Clock.System.now() + 1.days) { "Donation date is too far in the future" }
    }
}
