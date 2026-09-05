@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.website.faq.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * One question/answer pair on the public FAQ page, managed from the admin panel.
 *
 * German is mandatory because the website falls back to it whenever a translation is missing -
 * the same fallback the strings-*.xml files rely on.
 */
@Document("faqentries")
@TypeAlias("faqentry")
data class FaqEntry(
    @Id val id: ObjectId = ObjectId(),

    val category: FaqCategory,
    val sortOrder: Int, // position inside the category, ascending

    val german: FaqText,
    val austrian: FaqText? = null,
    val english: FaqText? = null,

    val createdAt: Instant,
    val updatedAt: Instant,

    val deleted: Boolean = false, // soft delete, matching Donation/MapEntry
)

data class FaqText(
    val question: String,
    val answer: String,
)
