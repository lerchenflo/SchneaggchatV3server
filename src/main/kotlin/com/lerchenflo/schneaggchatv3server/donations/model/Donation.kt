@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.donations.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Document("donations")
@TypeAlias("donation")
data class Donation(
    @Id val id: ObjectId = ObjectId(),

    val name: String,
    val amountCents: Long, // integer cents - never a Double for money
    val donatedAt: Instant,
    val message: String? = null,

    val createdAt: Instant,
    val updatedAt: Instant,

    val deleted: Boolean = false, // soft delete, matching MapEntry/Group
)
