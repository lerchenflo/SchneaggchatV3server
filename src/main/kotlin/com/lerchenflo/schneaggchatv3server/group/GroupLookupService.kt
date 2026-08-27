@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.group

import com.lerchenflo.schneaggchatv3server.group.model.Group
import com.lerchenflo.schneaggchatv3server.group.model.GroupMember
import com.lerchenflo.schneaggchatv3server.group.model.GroupResponse
import com.lerchenflo.schneaggchatv3server.group.model.toGroupMemberResponse
import com.lerchenflo.schneaggchatv3server.repository.GroupMemberRepository
import com.lerchenflo.schneaggchatv3server.repository.GroupRepository
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.jvm.optionals.getOrNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Service
class GroupLookupService(
    private val groupRepository: GroupRepository,
    private val groupMemberRepository: GroupMemberRepository,
    private val userLookupService: UserLookupService,
) {
    fun getUserGroupIds(userId: ObjectId): List<ObjectId> {
        return getNonDeletedGroups(getMemberGroupIds(userId)).map { it.id }
    }

    /**
     * Groups [userId] joined at-or-after message-sync version [version] was already reached, i.e.
     * groups whose history is not yet covered by the client's `since` cursor. `/messages/sync`
     * sends the full message history for these groups regardless of `since` -
     * see `GroupMember.joinedAtVersion`.
     *
     * `>=`, not `>`: a client whose cursor is exactly caught up (`since == joinedAtVersion`, e.g.
     * it was already fully synced right when it got added) must still get full-history replay for
     * the group, or it would only ever see messages versioned strictly after its own join and miss
     * everything up to and including it - including the "you were added" system message itself.
     */
    fun getGroupIdsJoinedAfterVersion(userId: ObjectId, version: Long): List<ObjectId> {
        return groupMemberRepository.findByUseridAndJoinedAtVersionGreaterThanEqual(userId, version)
            .map { it.groupId }
    }

    fun getUserGroupIdsLastchanged(userId: ObjectId): List<UserService.IdTimeStamp> {
        return getNonDeletedGroups(getMemberGroupIds(userId)).map { group ->
            UserService.IdTimeStamp(
                id = group.id.toHexString(),
                timeStamp = group.updatedAt.toEpochMilliseconds().toString()
            )
        }
    }

    fun getGroupName(groupId: ObjectId): String {
        return groupRepository.findById(groupId).getOrNull()?.name ?: "Unresolved group name"
    }

    fun getGroupAsGroupResponse(groupId: ObjectId): GroupResponse {

        val group = getGroupById(groupId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")
        val members = getGroupMembers(groupId)

        return GroupResponse(
            id = group.id.toHexString(),
            name = group.name,
            description = group.description,
            updatedAt = group.updatedAt.toEpochMilliseconds(),
            profilePicUpdatedAt = group.profilePicUpdatedAt.toEpochMilliseconds(),
            createdAt = group.createdAt.toEpochMilliseconds(),
            creatorId = group.creatorId.toHexString(),
            expiresAt = group.expiresAt?.toEpochMilliseconds(),
            members = members.map { it.toGroupMemberResponse(userLookupService.getUsername(it.userid)) }
        )
    }

    fun getGroupMembers(groupId: ObjectId): List<GroupMember> {
        return groupMemberRepository.findAllByGroupId(groupId)
    }

    fun isUserInGroup(userId: ObjectId, groupId: ObjectId): Boolean {
        return groupMemberRepository.findByuserid(userId)
            .any { it.groupId == groupId }
    }

    fun isAdmin(userId: ObjectId, members: List<GroupMember>): Boolean {
        val member = members.find { groupMember -> groupMember.userid == userId } ?: return false
        return member.admin
    }

    fun getGroupById(groupId: ObjectId): Group? {
        return groupRepository.findByIdAndDeletedFalse(groupId)
    }

    fun saveGroup(group: Group): Group {
        return groupRepository.save(group)
    }

    fun getExpiredGroups(before: Instant): List<Group> {
        return groupRepository.findByDeletedFalseAndExpiresAtBefore(before)
    }

    fun removeGroupMember(groupId: ObjectId, userId: ObjectId) : Boolean {
        val member = getGroupMembers(groupId).first { it.userid == userId }
        groupMemberRepository.delete(member)
        return true
    }

    fun leaveAllGroups(userId: ObjectId) {
        getUserGroupIds(userId).forEach { groupId ->
            removeGroupMember(groupId, userId)
        }
    }

    private fun getMemberGroupIds(userId: ObjectId): List<ObjectId> {
        return groupMemberRepository.findByuserid(userId).map { it.groupId }
    }

    private fun getNonDeletedGroups(groupIds: List<ObjectId>): List<Group> {
        if (groupIds.isEmpty()) return emptyList()
        return groupRepository.findByIdInAndDeletedFalse(groupIds)
    }
}
