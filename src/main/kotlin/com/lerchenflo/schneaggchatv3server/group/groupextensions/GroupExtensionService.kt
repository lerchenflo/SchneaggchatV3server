@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.group.groupextensions

import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.group.groupextensions.model.*
import com.lerchenflo.schneaggchatv3server.group.model.GroupMember
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import com.lerchenflo.schneaggchatv3server.util.requireOrLog
import com.lerchenflo.schneaggchatv3server.util.withOptimisticRetry
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Group extensions: one document per extension with its items embedded. Every write is a
 * load-modify-save on the whole document, guarded by [GroupExtension.version] and retried by
 * [withOptimisticRetry], so two members writing at the same time never lose a change.
 *
 * Permissions: any group member creates, renames and writes items; an extension is deleted by its
 * creator or a group admin, an item by its creator, the extension creator or a group admin.
 */
@Service
class GroupExtensionService(
    private val groupExtensionLookupService: GroupExtensionLookupService,
    private val groupLookupService: GroupLookupService,
    private val userLookupService: UserLookupService,
    private val notificationService: NotificationService,
    private val loggingService: LoggingService,
) {

    // ─── Extensions ──────────────────────────────────────────────────────────

    /** Creates the extension when [extensionId] is null, else renames it / changes its settings. */
    fun upsertExtension(
        extensionId: ObjectId?,
        ownerType: ExtensionOwnerType,
        ownerId: ObjectId,
        type: GroupExtensionType,
        title: String,
        settings: ExtensionSettings,
        requesterId: ObjectId,
    ): GroupExtension {
        require(ownerType == ExtensionOwnerType.GROUP) { "Only group extensions are supported" }
        validateSettings(type, settings)

        val saved = withOptimisticRetry {
            val now = Clock.System.now()
            val existing = extensionId?.let { findActive(it) }

            if (existing != null) {
                require(existing.ownerType == ownerType && existing.ownerId == ownerId && existing.type == type) {
                    "Owner and type of an extension can not be changed"
                }
            }
            requireMember(ownerId, requesterId, action = if (existing == null) "CREATE" else "EDIT")

            groupExtensionLookupService.save(
                existing?.copy(
                    title     = title,
                    settings  = settings,
                    updatedBy = requesterId,
                    updatedAt = now,
                ) ?: GroupExtension(
                    ownerType = ownerType,
                    ownerId   = ownerId,
                    type      = type,
                    title     = title,
                    settings  = settings,
                    createdBy = requesterId,
                    createdAt = now,
                    updatedBy = requesterId,
                    updatedAt = now,
                )
            )
        }

        AppLogger.info("GROUP EXTENSION: $type \"$title\" ${if (extensionId == null) "created" else "updated"}", user = requesterId)
        loggingService.log(
            userId = requesterId,
            logType = if (extensionId == null) LogType.GROUP_EXTENSION_CREATED else LogType.GROUP_EXTENSION_EDITED,
        )
        notificationService.notifyGroupExtensionUpdate(saved, deleted = false)
        return saved
    }

    /** Soft delete, so the sync reports it as deleted. Extension creator or group admin only. */
    fun deleteExtension(extensionId: ObjectId, requesterId: ObjectId) {
        val saved = withOptimisticRetry {
            val extension = findActive(extensionId)
            val members = requireMember(extension.ownerId, requesterId, action = "DELETE")
            requireOrLog(
                "delete_group_extension_not_allowed",
                extension.createdBy == requesterId || groupLookupService.isAdmin(requesterId, members),
                { "Unauthorized group extension action - user: ${userLookupService.getUsername(requesterId)}, action: DELETE, extension: ${extension.id}: Not creator or admin" }
            ) { "Only the creator or a group admin can delete this extension" }

            groupExtensionLookupService.save(
                extension.copy(
                    deleted   = true,
                    updatedBy = requesterId,
                    updatedAt = Clock.System.now(),
                )
            )
        }

        AppLogger.info("GROUP EXTENSION: ${saved.type} \"${saved.title}\" deleted", user = requesterId)
        loggingService.log(userId = requesterId, logType = LogType.GROUP_EXTENSION_DELETED)
        notificationService.notifyGroupExtensionUpdate(saved, deleted = true)
    }

    // ─── Items ───────────────────────────────────────────────────────────────

    /** Creates the item when [itemId] is null, else replaces its data. Returns the whole extension. */
    fun upsertItem(
        extensionId: ObjectId,
        itemId: ObjectId?,
        data: ExtensionItemData,
        requesterId: ObjectId,
    ): GroupExtension {
        val saved = withOptimisticRetry {
            val extension = findActive(extensionId)
            val members = requireMember(extension.ownerId, requesterId, action = "WRITE_ITEM")

            require(data.extensionType == extension.type) {
                "A ${data.extensionType} item does not fit a ${extension.type} extension"
            }

            val existing = itemId?.let { id ->
                extension.items.find { it.id == id }
                    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found")
            }
            if (existing == null && extension.items.size >= extension.type.maxItems) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "This extension can hold at most ${extension.type.maxItems} items")
            }

            val prepared = data.prepareForSave(existing?.data, requesterId.toHexString())
            validateItem(prepared, existing, members)

            val now = Clock.System.now()
            val items = if (existing == null) {
                extension.items + GroupExtensionItem(
                    data      = prepared,
                    createdBy = requesterId,
                    createdAt = now,
                    updatedBy = requesterId,
                    updatedAt = now,
                )
            } else {
                extension.items.map { item ->
                    if (item.id == existing.id) item.copy(data = prepared, updatedBy = requesterId, updatedAt = now) else item
                }
            }

            groupExtensionLookupService.save(extension.copy(items = items, updatedBy = requesterId, updatedAt = now))
        }

        loggingService.log(
            userId = requesterId,
            logType = if (itemId == null) LogType.GROUP_EXTENSION_ITEM_CREATED else LogType.GROUP_EXTENSION_ITEM_EDITED,
        )
        notificationService.notifyGroupExtensionUpdate(saved, deleted = false)
        return saved
    }

    /** Item creator, extension creator or group admin only. Returns the whole extension. */
    fun deleteItem(extensionId: ObjectId, itemId: ObjectId, requesterId: ObjectId): GroupExtension {
        val saved = withOptimisticRetry {
            val extension = findActive(extensionId)
            val members = requireMember(extension.ownerId, requesterId, action = "DELETE_ITEM")

            val item = extension.items.find { it.id == itemId }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found")
            requireOrLog(
                "delete_group_extension_item_not_allowed",
                item.createdBy == requesterId || extension.createdBy == requesterId || groupLookupService.isAdmin(requesterId, members),
                { "Unauthorized group extension action - user: ${userLookupService.getUsername(requesterId)}, action: DELETE_ITEM, extension: ${extension.id}: Not creator or admin" }
            ) { "Only the creator or a group admin can delete this item" }

            groupExtensionLookupService.save(
                extension.copy(
                    items     = extension.items - item,
                    updatedBy = requesterId,
                    updatedAt = Clock.System.now(),
                )
            )
        }

        loggingService.log(userId = requesterId, logType = LogType.GROUP_EXTENSION_ITEM_DELETED)
        notificationService.notifyGroupExtensionUpdate(saved, deleted = false)
        return saved
    }

    // ─── Sync ────────────────────────────────────────────────────────────────

    /**
     * Same shape as /map/sync, limited to the requester's groups: extensions the client does not
     * have or that changed since its timestamp, and (on page 0) every id the client has that is
     * deleted, unknown, or in a group the requester left.
     */
    fun extensionSync(
        clientEntries: List<UserService.IdTimeStamp>,
        page: Int,
        pageSize: Int,
        requesterId: ObjectId,
    ): GroupExtensionSyncResponse {
        val clientMap = clientEntries.associate { it.id to it.timeStamp }
        val groupIds = groupLookupService.getUserGroupIds(requesterId)
        val allExtensions = groupExtensionLookupService.findActiveByGroups(groupIds)
        val serverIds = allExtensions.map { it.id.toHexString() }.toSet()

        val changed = allExtensions.filter { extension ->
            val clientTs = clientMap[extension.id.toHexString()] ?: return@filter true
            clientTs.toLongOrNull()?.let { extension.updatedAt.toEpochMilliseconds() > it } ?: true
        }.sortedByDescending { it.updatedAt.toEpochMilliseconds() }

        val start = page * pageSize
        return GroupExtensionSyncResponse(
            updatedExtensions = changed.drop(start).take(pageSize).map { it.toGroupExtensionResponse() },
            deletedExtensions = if (page == 0) clientMap.keys.filter { it !in serverIds } else emptyList(),
            moreEntries = (start + pageSize) < changed.size,
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun findActive(extensionId: ObjectId): GroupExtension =
        groupExtensionLookupService.findActive(extensionId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Extension not found")

    /** Throws unless [userId] is a member of group [groupId]; returns the group's members. */
    private fun requireMember(groupId: ObjectId, userId: ObjectId, action: String): List<GroupMember> {
        val members = groupLookupService.getGroupMembers(groupId)
        requireOrLog(
            "group_extension_not_member",
            members.any { it.userid == userId },
            { "Unauthorized group extension action - user: ${userLookupService.getUsername(userId)}, action: $action, group: ${groupLookupService.getGroupName(groupId)}: Not in group" }
        ) { "You are not a member of this group" }
        return members
    }

    private fun validateSettings(type: GroupExtensionType, settings: ExtensionSettings) {
        require(type.settingsType.isInstance(settings)) {
            "${settings::class.simpleName} settings do not fit a $type extension"
        }
        val errors = settings.validate()
        if (errors.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid settings: ${errors.joinToString("; ")}")
        }
    }

    /**
     * Field limits of the payload, and every referenced user must be a current member - or already
     * be referenced by the stored item, so an old expense of someone who left stays editable.
     */
    private fun validateItem(data: ExtensionItemData, existing: GroupExtensionItem?, members: List<GroupMember>) {
        val errors = data.validate().toMutableList()

        val allowedIds = members.map { it.userid.toHexString() }.toSet() +
            (existing?.data?.referencedUserIds() ?: emptySet())
        val unknownIds = data.referencedUserIds() - allowedIds
        if (unknownIds.isNotEmpty()) errors += "users not in this group: ${unknownIds.joinToString()}"

        if (errors.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid ${data::class.simpleName}: ${errors.joinToString("; ")}"
            )
        }
    }
}
