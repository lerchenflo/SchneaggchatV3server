package com.lerchenflo.schneaggchatv3server.group.groupextensions

import com.lerchenflo.schneaggchatv3server.group.groupextensions.model.GroupExtension
import com.lerchenflo.schneaggchatv3server.repository.GroupExtensionRepository
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/** Owns [GroupExtensionRepository]; other features read and delete group extensions through here. */
@Service
class GroupExtensionLookupService(
    private val groupExtensionRepository: GroupExtensionRepository,
) {
    /** The extension, or null when it does not exist or is soft-deleted. */
    fun findActive(extensionId: ObjectId): GroupExtension? {
        return groupExtensionRepository.findByIdAndDeletedFalse(extensionId)
    }

    fun findActiveByGroups(groupIds: List<ObjectId>): List<GroupExtension> {
        if (groupIds.isEmpty()) return emptyList()
        return groupExtensionRepository.findByOwnerIdInAndDeletedFalse(groupIds)
    }

    fun save(extension: GroupExtension): GroupExtension {
        return groupExtensionRepository.save(extension)
    }

    /**
     * Hard-deletes every extension of a deleted group, soft-deleted ones included. Clients drop them
     * as unknown ids on their next /groupextensions/sync.
     */
    fun deleteAllGroupExtensions(groupId: ObjectId) {
        groupExtensionRepository.deleteByOwnerId(groupId)
    }
}
