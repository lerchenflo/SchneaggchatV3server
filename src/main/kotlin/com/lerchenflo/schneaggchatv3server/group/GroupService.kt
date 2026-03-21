@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.group

import com.lerchenflo.schneaggchatv3server.group.model.Group
import com.lerchenflo.schneaggchatv3server.group.model.GroupMember
import com.lerchenflo.schneaggchatv3server.group.model.GroupMemberResponse
import com.lerchenflo.schneaggchatv3server.group.model.GroupResponse
import com.lerchenflo.schneaggchatv3server.group.model.toGroupMemberResponse
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.repository.GroupMemberRepository
import com.lerchenflo.schneaggchatv3server.repository.GroupRepository
import com.lerchenflo.schneaggchatv3server.user.FriendsService
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.ColorGenerator
import com.lerchenflo.schneaggchatv3server.util.ImageManager
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import io.jsonwebtoken.security.Keys.password
import org.bson.codecs.ObjectIdGenerator
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Component
class GroupService(
    private val groupRepository: GroupRepository,
    private val groupMemberRepository: GroupMemberRepository,
    private val groupLookupService: GroupLookupService,
    private val userLookupService: UserLookupService,

    private val notificationService: NotificationService,

    private val imageManager: ImageManager,
    private val friendsService: FriendsService,
    private val loggingService: LoggingService,
) {

    fun createGroup(groupName: String, members: List<ObjectId>, creatorId: ObjectId, description: String, profilePic: MultipartFile) : Group {

        //Try to add creator (Set prevents duplicate members)
        val membersInternal: Set<ObjectId> = members.toSet() + creatorId

        require(membersInternal.size > 2) { "A group must have at least 3 members" }
        require(ValidationUtils.validateUsername(groupName)) { "Group name invalid" }
        require(ValidationUtils.validateDescription(description)) { "Description invalid" }
        require(ValidationUtils.validatePicture(profilePic)) { "Profilepic invalid" }

        //Creator needs to be friends with everyone
        members.forEach { member ->
            if (member == creatorId) return@forEach //Exclude self
            require(friendsService.areFriends(creatorId, member)){ "You need to be friends with everyone in the group"}
        }

        val currentTime = Clock.System.now()
        val group = groupRepository.save(
            Group(
                name = groupName.trim(),
                description = description,
                updatedAt = currentTime,
                profilePicUpdatedAt = currentTime,
                createdAt = currentTime,
                creatorId = creatorId
            )
        )

        imageManager.saveProfilePic(
            image = profilePic,
            userId = group.id.toHexString(),
            group = true
        )

        // Generate unique colors for group members (per-group uniqueness)
        val existingColors = emptySet<Int>() // New group has no existing colors
        val memberColors = ColorGenerator.generateUniqueColorsForGroup(existingColors, membersInternal.size)
        val memberColorMap = membersInternal.zip(memberColors).toMap()

        val members = groupMemberRepository.saveAll(
            membersInternal.mapIndexed { index, userId ->
                GroupMember(
                    userid = userId,
                    groupId = group.id,
                    joinedAt = currentTime,
                    admin = (userId == creatorId),
                    color = memberColorMap[userId]!!
                )
            }
        )

        loggingService.log(
            userId = creatorId,
            logType = LogType.GROUP_CREATED,
        )

        notificationService.notifyGroupUpdate(
            GroupResponse(
                id = group.id.toHexString(),
                name = group.name,
                description = group.description,
                updatedAt = group.updatedAt.toEpochMilliseconds(),
                profilePicUpdatedAt = group.profilePicUpdatedAt.toEpochMilliseconds(),
                createdAt = group.createdAt.toEpochMilliseconds(),
                creatorId = group.creatorId.toHexString(),
                members = members.map { member ->
                    member.toGroupMemberResponse(
                        memberName = userLookupService.getUsername(member.userid)
                    )
                }
            ),
            deleted = false
        )

        return group
    }



    data class GroupSyncResponse(
        val updatedGroups: List<GroupResponse>,
        val deletedGroups: List<String>
    )

    fun syncGroups(userId: String, ids: List<UserService.IdTimeStamp>): GroupSyncResponse {
        // Groups which the client has on their device
        val clientGroups = ids.associate {
            it.id to it.timeStamp
        }

        // All groups this user is part of on the server
        val serverGroups = groupLookupService.getUserGroupIdsLastchanged(ObjectId(userId)).associate {
            it.id to it.timeStamp
        }

        // Find groups that need to be added (client doesn't have) or updated (server is newer)
        val groupsToSyncIds = serverGroups.filter { (groupId, serverTs) ->
            val clientTs = clientGroups[groupId]
            clientTs == null || serverTs > clientTs
        }.keys

        // Find groups that the client has but the server doesn't (should be removed from client)
        val serverGroupIds = serverGroups.keys
        val deletedGroups = clientGroups.keys.filter { it !in serverGroupIds }

        return GroupSyncResponse(
            updatedGroups = groupsToSyncIds.map { groupId ->
                groupLookupService.getGroupAsGroupResponse(ObjectId(groupId))
            },
            deletedGroups = deletedGroups
        )
    }


    fun getGroupProfilePic(groupId: ObjectId): ResponseEntity<ByteArray> {
        return try {
            val imageName = imageManager.getProfilePicFileName(groupId.toHexString(), true)
            val imageBytes = imageManager.loadProfilePicFromFile(imageName)
            ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(imageBytes)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.notFound().build()
        }
    }


    fun changeGroupProfilePic(userId: ObjectId, groupId: ObjectId, image: MultipartFile) {

        require(groupLookupService.isUserInGroup(userId, groupId))
        require(ValidationUtils.validatePicture(image)) { "Image invalid" }

        val group = groupLookupService.getGroupById(groupId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")

        imageManager.saveProfilePic(
            image = image,
            userId = groupId.toHexString(),
            group = true
        )

        val now = Clock.System.now()
        groupRepository.save(group.copy(
            updatedAt = now,
            profilePicUpdatedAt = now
        ))

        notificationService.notifyGroupUpdate(groupLookupService.getGroupAsGroupResponse(groupId), false)

    }

    fun changeGroupDescription(userId: ObjectId, groupId: ObjectId, newDescription: String) {

        require(groupLookupService.isUserInGroup(userId, groupId))
        require(ValidationUtils.validateDescription(newDescription)) { "Invalid string" }

        val group = groupLookupService.getGroupById(groupId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")

        groupRepository.save(group.copy(
            updatedAt = Clock.System.now(),
            description = newDescription
        ))

        notificationService.notifyGroupUpdate(groupLookupService.getGroupAsGroupResponse(groupId), false)

    }

    fun changeGroupName(userId: ObjectId, groupId: ObjectId, newName: String) {

        require(groupLookupService.isUserInGroup(userId, groupId))
        require(ValidationUtils.validateUsername(newName)) { "Group name invalid" }

        val group = groupLookupService.getGroupById(groupId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")

        groupRepository.save(group.copy(
            updatedAt = Clock.System.now(),
            name = newName
        ))

        notificationService.notifyGroupUpdate(groupLookupService.getGroupAsGroupResponse(groupId), false)
    }


    enum class GroupMemberAction {
        ADD_USER,
        REMOVE_USER,
        MAKE_ADMIN,
        REMOVE_ADMIN
    }

    data class GroupActionRequest(
        val action: GroupMemberAction,
        val groupMemberId: String,
        val groupId: String
    )

    fun performUserAction(userAction: GroupMemberAction, requestingUser: ObjectId, groupMember: ObjectId, groupId: ObjectId){

        val group = groupLookupService.getGroupById(groupId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")

        val groupMembers = groupLookupService.getGroupMembers(groupId)

        require(groupLookupService.isUserInGroup(requestingUser, groupId)) { "You are not a member of this group"}

        val now = Clock.System.now()

        when (userAction) {
            GroupMemberAction.ADD_USER -> {
                require(groupLookupService.isAdmin(requestingUser, groupMembers)) {"You are not an admin"}

                require(!groupLookupService.isUserInGroup(groupMember, groupId)) {"User is already in this group"}

                val existingColors = groupMembers.map { it.color }.toSet()
                val newColor = ColorGenerator.generateUniqueColorsForGroup(existingColors, 1).first()

                try {
                    groupMemberRepository.save(GroupMember(
                        userid = groupMember,
                        groupId = groupId,
                        joinedAt = now,
                        admin = false,
                        color = newColor
                    ))
                } catch (e: DuplicateKeyException) {
                    throw IllegalArgumentException("User is already in this group")
                }
            }
            GroupMemberAction.REMOVE_USER -> {
                require(groupLookupService.isUserInGroup(groupMember, groupId)) {"User is not in this group"}

                // If someone else is removing the user, they must be admin
                if (requestingUser != groupMember) {
                    require(groupLookupService.isAdmin(requestingUser, groupMembers)) {"You are not an admin"}
                }

                // If user is leaving and is the last admin, promote someone
                if (requestingUser == groupMember) {
                    if (!groupMembers.any { it.admin && it.userid != requestingUser }) {
                        // Without me, no user is admin
                        val newGroupMembers = groupMembers.filter { it.userid != requestingUser }

                        if (newGroupMembers.isEmpty()) {
                            // Last person leaving - delete the group and all members
                            val focusedMember = groupMembers.first { it.userid == groupMember }
                            groupMemberRepository.delete(focusedMember)
                            groupRepository.delete(group)
                            return // Don't update group lastChanged
                        } else {
                            // Find user with earliest joinedAt timestamp and promote to admin
                            val longestMember = newGroupMembers.minBy { it.joinedAt }
                            groupMemberRepository.save(longestMember.copy(admin = true))
                        }
                    }
                }

                // Remove the member
                val focusedMember = groupMembers.first { it.userid == groupMember }
                groupMemberRepository.delete(focusedMember)
            }

            GroupMemberAction.MAKE_ADMIN -> {
                require(groupLookupService.isAdmin(requestingUser, groupMembers)) {"You are not an admin"}

                require(groupLookupService.isUserInGroup(groupMember, groupId)) {"User is not in this group"}

                val focusedMember = groupMembers.first { it.userid == groupMember }
                require(!focusedMember.admin) {"User is already admin"}

                groupMemberRepository.save(focusedMember.copy(
                    admin = true
                ))
            }
            GroupMemberAction.REMOVE_ADMIN -> {
                require(groupLookupService.isAdmin(requestingUser, groupMembers)) {"You are not an admin"}

                require(groupLookupService.isUserInGroup(groupMember, groupId)) {"User is not in this group"}

                val focusedMember = groupMembers.first { it.userid == groupMember }
                require(focusedMember.admin) {"User is not an admin"}

                // Check if this is the last admin
                val adminCount = groupMembers.count { it.admin }
                require(adminCount > 1) {"Cannot remove the last admin. Promote someone else first."}

                groupMemberRepository.save(focusedMember.copy(
                    admin = false
                ))
            }
        }

        //No error, update group last changed
        groupRepository.save(group.copy(updatedAt = now))

        notificationService.notifyGroupUpdate(groupLookupService.getGroupAsGroupResponse(groupId), false)

    }

}