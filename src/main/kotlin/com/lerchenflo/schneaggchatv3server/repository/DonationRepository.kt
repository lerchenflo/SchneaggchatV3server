package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.website.donations.model.Donation
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface DonationRepository : MongoRepository<Donation, ObjectId> {
    fun findByDeletedFalseOrderByDonatedAtDesc(): List<Donation>

    fun findAllByOrderByDonatedAtDesc(): List<Donation>

    fun countByDeletedFalse(): Long
}
