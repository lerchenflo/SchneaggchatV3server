@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.group.groupextensions.model

import kotlin.time.ExperimentalTime

fun GroupExtension.toGroupExtensionResponse(): GroupExtensionResponse = GroupExtensionResponse(
    id = id.toHexString(),
    ownerType = ownerType,
    ownerId = ownerId.toHexString(),
    type = type,
    title = title,
    settings = settings,
    items = items.map { it.toGroupExtensionItemResponse() },
    createdBy = createdBy.toHexString(),
    createdAt = createdAt.toEpochMilliseconds(),
    updatedBy = updatedBy.toHexString(),
    updatedAt = updatedAt.toEpochMilliseconds(),
)

fun GroupExtensionItem.toGroupExtensionItemResponse(): GroupExtensionItemResponse = GroupExtensionItemResponse(
    id = id.toHexString(),
    data = data,
    createdBy = createdBy.toHexString(),
    createdAt = createdAt.toEpochMilliseconds(),
    updatedBy = updatedBy.toHexString(),
    updatedAt = updatedAt.toEpochMilliseconds(),
)
