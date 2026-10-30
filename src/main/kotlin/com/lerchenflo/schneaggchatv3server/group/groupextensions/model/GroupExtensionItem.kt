@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.group.groupextensions.model

import com.lerchenflo.schneaggchatv3server.group.groupextensions.ExtensionItemData
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * One entry of an extension (an expense, a quote, a route point, the shared text). Embedded in
 * [GroupExtension.items], never a document of its own. [id] is stored as `_id`.
 */
data class GroupExtensionItem(
    @Id val id: ObjectId = ObjectId(),

    val data: ExtensionItemData,

    val createdBy: ObjectId,
    val createdAt: Instant,

    val updatedBy: ObjectId,
    val updatedAt: Instant,
)
