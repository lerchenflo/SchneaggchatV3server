package com.lerchenflo.schneaggchatv3server.group.groupextensions.model

import com.lerchenflo.schneaggchatv3server.group.groupextensions.ExtensionItemData

/** One extension with all its items. Used by every endpoint and the socket. */
data class GroupExtensionResponse(
    val id: String,
    val ownerType: ExtensionOwnerType,
    val ownerId: String,
    val type: GroupExtensionType,
    val title: String,
    val settings: ExtensionSettings,
    val items: List<GroupExtensionItemResponse>,
    val createdBy: String,
    val createdAt: Long,
    val updatedBy: String,
    val updatedAt: Long,
)

data class GroupExtensionItemResponse(
    val id: String,
    val data: ExtensionItemData,
    val createdBy: String,
    val createdAt: Long,
    val updatedBy: String,
    val updatedAt: Long,
)

data class GroupExtensionSyncResponse(
    val updatedExtensions: List<GroupExtensionResponse>,
    val deletedExtensions: List<String>,
    val moreEntries: Boolean,
)
