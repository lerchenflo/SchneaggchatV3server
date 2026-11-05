@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.group.groupextensions.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * One extension instance of a group (a split "Italien 2026", a quote collection, ...). A group can
 * have any number of them, also several of the same [type]. The items are embedded, the same way
 * MapEntry.locationData is.
 *
 * [updatedAt] is the sync timestamp (IdTimeStamp sync like /map/sync), so every item write must
 * bump it as well.
 */
@Document("group_extensions")
@TypeAlias("groupextension")
data class GroupExtension(
    @Id val id: ObjectId = ObjectId(),

    val ownerType: ExtensionOwnerType = ExtensionOwnerType.GROUP,
    @Indexed //Index for the sync query over all groups of a user
    val ownerId: ObjectId,

    val type: GroupExtensionType,
    val title: String,
    val settings: ExtensionSettings,

    val items: List<GroupExtensionItem> = emptyList(),

    val createdBy: ObjectId,
    val createdAt: Instant,

    val updatedBy: ObjectId,
    val updatedAt: Instant,

    val deleted: Boolean = false,

    /** Optimistic locking: every write is load-modify-save inside withOptimisticRetry. */
    @Version val version: Long? = null,
)
