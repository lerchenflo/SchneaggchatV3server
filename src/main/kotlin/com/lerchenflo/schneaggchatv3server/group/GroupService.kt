@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.group

import com.lerchenflo.schneaggchatv3server.events.EventsLookupService
import com.lerchenflo.schneaggchatv3server.events.eventmodel.Event
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventParticipationStatus
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventVisibility
import com.lerchenflo.schneaggchatv3server.events.eventmodel.hasEnded
import com.lerchenflo.schneaggchatv3server.events.eventmodel.toResponse
import com.lerchenflo.schneaggchatv3server.group.model.Group
import com.lerchenflo.schneaggchatv3server.group.model.GroupMember
import com.lerchenflo.schneaggchatv3server.group.model.GroupResponse
import com.lerchenflo.schneaggchatv3server.group.model.toGroupMemberResponse
import com.lerchenflo.schneaggchatv3server.message.MessageLookupService
import com.lerchenflo.schneaggchatv3server.message.messagemodel.SystemEventType
import com.lerchenflo.schneaggchatv3server.message.system.SystemMessageService
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.repository.GroupMemberRepository
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsService
import com.lerchenflo.schneaggchatv3server.util.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
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
import kotlin.time.Instant

@Component
class GroupService(
    private val groupMemberRepository: GroupMemberRepository,
    private val groupLookupService: GroupLookupService,
    private val eventsLookupService: EventsLookupService,
    private val userLookupService: UserLookupService,

    private val notificationService: NotificationService,
    private val systemMessageService: SystemMessageService,
    private val messageLookupService: MessageLookupService,

    private val imageManager: ImageManager,
    private val friendsService: FriendsService,
    private val friendsLookupService: FriendsLookupService,
    private val loggingService: LoggingService,
    private val versionCounterService: VersionCounterService,
) {

    fun createGroup(groupName: String, members: List<ObjectId>, creatorId: ObjectId, description: String, profilePic: MultipartFile?, createdFromEvent: Boolean, expiresAt: Instant? = null) : Group {

        //Try to add creator (Set prevents duplicate members)
        val membersInternal: Set<ObjectId> = members.toSet() + creatorId

        if (!createdFromEvent) {
            require(membersInternal.size > 2) { "A group must have at least 3 members" }
            require(ValidationUtils.validateUsername(groupName)) { "Group name invalid" }
            require(ValidationUtils.validateDescription(description)) { "Description invalid" }
            profilePic?.let {
                require(ValidationUtils.validatePicture(profilePic)) { "Profilepic invalid" }
            }
        }

        //Creator needs to be friends with everyone
        membersInternal.forEach { member ->
            if (member == creatorId) return@forEach //Exclude self
            requireOrLog(
                "create_group_member_not_friend",
                friendsLookupService.areFriends(creatorId, member),
                { "Group creation denied - user: ${userLookupService.getUsername(creatorId)} is not friends with: ${userLookupService.getUsername(member)}" }
            ) { "You need to be friends with everyone in the group" }
        }

        val currentTime = Clock.System.now()
        val group = groupLookupService.saveGroup(
            Group(
                name = groupName.trim(),
                description = description,
                updatedAt = currentTime,
                profilePicUpdatedAt = currentTime,
                createdAt = currentTime,
                creatorId = creatorId,
                expiresAt = expiresAt
            )
        )

        profilePic?.let {
            imageManager.saveProfilePic(
                image = profilePic,
                userId = group.id.toHexString(),
                group = true
            )
        }

        // Generate unique colors for group members (per-group uniqueness)
        val existingColors = emptySet<Int>() // New group has no existing colors
        val memberColors = ColorGenerator.generateUniqueColorsForGroup(existingColors, membersInternal.size)
        val memberColorMap = membersInternal.zip(memberColors).toMap()

        val joinedAtVersion = versionCounterService.current(SyncCollection.MESSAGES)

        val members = groupMemberRepository.saveAll(
            membersInternal.mapIndexed { index, userId ->
                GroupMember(
                    userid = userId,
                    groupId = group.id,
                    joinedAt = currentTime,
                    admin = (userId == creatorId),
                    color = memberColorMap[userId]!!,
                    joinedAtVersion = joinedAtVersion,
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
                expiresAt = group.expiresAt?.toEpochMilliseconds(),
                members = members.map { member ->
                    member.toGroupMemberResponse(
                        memberName = userLookupService.getUsername(member.userid)
                    )
                }
            ),
            deleted = false
        )

        systemMessageService.groupEvent(
            groupId = group.id,
            eventType = SystemEventType.GROUP_CREATED,
            actorId = creatorId,
            text = group.name,
        )

        return group
    }



    data class GroupSyncResponse(
        val updatedGroups: List<GroupResponse>,
        val deletedGroups: List<String>
    )

    fun syncGroups(userId: ObjectId, ids: List<UserService.IdTimeStamp>): GroupSyncResponse {
        // Groups which the client has on their device
        val clientGroups = ids.associate {
            it.id to it.timeStamp
        }

        // All groups this user is part of on the server
        val serverGroups = groupLookupService.getUserGroupIdsLastchanged(userId).associate {
            it.id to it.timeStamp
        }

        // Find groups that need to be added (client doesn't have) or updated (server is newer)
        // Timestamps are transported as strings, so they must be compared as longs - not lexicographically
        val groupsToSyncIds = serverGroups.filter { (groupId, serverTsString) ->
            val serverTs = serverTsString.toLongOrNull() ?: return@filter true
            val clientTs = clientGroups[groupId]?.toLongOrNull()
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

        requireOrLog(
            "change_group_profilepic_not_member",
            groupLookupService.isUserInGroup(userId, groupId),
            { "Unauthorized group action - user: ${userLookupService.getUsername(userId)}, action: CHANGE_PROFILE_PIC, group: ${groupLookupService.getGroupName(groupId)}: Not in group" }
        ) { "You are not a member of this group" }
        require(ValidationUtils.validatePicture(image)) { "Image invalid" }

        val group = groupLookupService.getGroupById(groupId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")

        imageManager.saveProfilePic(
            image = image,
            userId = groupId.toHexString(),
            group = true
        )

        val now = Clock.System.now()
        groupLookupService.saveGroup(group.copy(
            updatedAt = now,
            profilePicUpdatedAt = now
        ))

        notificationService.notifyGroupUpdate(groupLookupService.getGroupAsGroupResponse(groupId), false)

        systemMessageService.groupEvent(
            groupId = groupId,
            eventType = SystemEventType.GROUP_PICTURE_CHANGED,
            actorId = userId,
        )

    }

    fun changeGroupDescription(userId: ObjectId, groupId: ObjectId, newDescription: String) {

        requireOrLog(
            "change_group_description_not_member",
            groupLookupService.isUserInGroup(userId, groupId),
            { "Unauthorized group action - user: ${userLookupService.getUsername(userId)}, action: CHANGE_DESCRIPTION, group: ${groupLookupService.getGroupName(groupId)}: Not in group" }
        ) { "You are not a member of this group" }
        require(ValidationUtils.validateDescription(newDescription)) { "Invalid string" }

        val group = groupLookupService.getGroupById(groupId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")

        groupLookupService.saveGroup(group.copy(
            updatedAt = Clock.System.now(),
            description = newDescription
        ))

        notificationService.notifyGroupUpdate(groupLookupService.getGroupAsGroupResponse(groupId), false)

        systemMessageService.groupEvent(
            groupId = groupId,
            eventType = SystemEventType.GROUP_DESCRIPTION_CHANGED,
            actorId = userId,
        )

    }

    fun changeGroupName(userId: ObjectId, groupId: ObjectId, newName: String) {

        requireOrLog(
            "change_group_name_not_member",
            groupLookupService.isUserInGroup(userId, groupId),
            { "Unauthorized group action - user: ${userLookupService.getUsername(userId)}, action: CHANGE_NAME, group: ${groupLookupService.getGroupName(groupId)}: Not in group" }
        ) { "You are not a member of this group" }
        require(ValidationUtils.validateUsername(newName)) { "Group name invalid" }

        val group = groupLookupService.getGroupById(groupId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")

        groupLookupService.saveGroup(group.copy(
            updatedAt = Clock.System.now(),
            name = newName
        ))

        notificationService.notifyGroupUpdate(groupLookupService.getGroupAsGroupResponse(groupId), false)

        systemMessageService.groupEvent(
            groupId = groupId,
            eventType = SystemEventType.GROUP_NAME_CHANGED,
            actorId = userId,
            text = newName,
            previousText = group.name,
        )
    }

    /**
     * Keep a group's expiry in sync with the event it belongs to (event startDate, or closeDate
     * if set, plus the event's creator-chosen [com.lerchenflo.schneaggchatv3server.events.eventmodel.GroupDeleteDelay])
     */
    fun setGroupExpiresAt(groupId: ObjectId, expiresAt: Instant?) {
        val group = groupLookupService.getGroupById(groupId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")

        if (group.expiresAt == expiresAt) return

        groupLookupService.saveGroup(group.copy(expiresAt = expiresAt, updatedAt = Clock.System.now()))

        notificationService.notifyGroupUpdate(groupLookupService.getGroupAsGroupResponse(groupId), false)
    }

    /**
     * User-facing: set or move a group's delete timer, or clear it entirely (null = never
     * auto-expires). Admins only - the timer deletes the whole chat for everyone, so it must not
     * be open to every member (the endpoint is reachable without the client UI).
     * Note: for a group backed by an event, the next event edit that changes the event's dates or
     * delete delay re-syncs this to the event's own setting, overwriting whatever is set here.
     */
    fun changeGroupExpiresAt(userId: ObjectId, groupId: ObjectId, expiresAt: Instant?) {
        requireOrLog(
            "change_group_expiry_not_member",
            groupLookupService.isUserInGroup(userId, groupId),
            { "Unauthorized group action - user: ${userLookupService.getUsername(userId)}, action: CHANGE_EXPIRES_AT, group: ${groupLookupService.getGroupName(groupId)}: Not in group" }
        ) { "You are not a member of this group" }
        requireOrLog(
            "change_group_expiry_not_admin",
            groupLookupService.isAdmin(userId, groupLookupService.getGroupMembers(groupId)),
            { "Unauthorized group action - user: ${userLookupService.getUsername(userId)}, action: CHANGE_EXPIRES_AT, group: ${groupLookupService.getGroupName(groupId)}: Not an admin" }
        ) { "You are not an admin" }
        expiresAt?.let {
            require(it > Clock.System.now()) { "Expiry date must be in the future" }
        }

        setGroupExpiresAt(groupId, expiresAt)
    }

    data class SetGroupExpiryRequest(
        @field:NotBlank(message = "Group ID must not be blank")
        @field:Size(max = 24, message = "Group ID too long")
        val groupId: String,
        val expiresAt: Long?
    )


    enum class GroupMemberAction {
        ADD_USER,
        REMOVE_USER,
        MAKE_ADMIN,
        REMOVE_ADMIN
    }

    data class GroupActionRequest(
        val action: GroupMemberAction,
        @field:NotBlank(message = "Group member ID must not be blank")
        @field:Size(max = 24, message = "Group member ID too long")
        val groupMemberId: String,
        @field:NotBlank(message = "Group ID must not be blank")
        @field:Size(max = 24, message = "Group ID too long")
        val groupId: String
    )

    fun performUserAction(userAction: GroupMemberAction, requestingUser: ObjectId, groupMember: ObjectId, groupId: ObjectId){

        val group = groupLookupService.getGroupById(groupId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")

        val groupMembers = groupLookupService.getGroupMembers(groupId)

        requireOrLog(
            "manage_group_member_not_member",
            groupLookupService.isUserInGroup(requestingUser, groupId),
            { "Unauthorized group action - user: ${userLookupService.getUsername(requestingUser)}, action: $userAction, group: ${groupLookupService.getGroupName(groupId)}: Not in group" }
        ) { "You are not a member of this group" }


        val now = Clock.System.now()

        // Captured while running the mutation below, then emitted once at the very end - after
        // the group has been notified, so a client (e.g. one just ADD_USER'd) always learns the
        // group exists before it receives a system message addressed to it.
        var pendingEvent: SystemEventType? = null

        // Same idea for the connected event's participation change, emitted after the group update
        // so the added member knows the group before they are told they are attending its event.
        var pendingEventUpdate: Event? = null

        // A member who left or got removed is no longer in the group response, but still has to
        // drop the group on every device of theirs.
        var removedMemberId: ObjectId? = null

        when (userAction) {
            GroupMemberAction.ADD_USER -> {
                requireOrLog(
                    "add_group_user_not_admin",
                    groupLookupService.isAdmin(requestingUser, groupMembers),
                    { "Unauthorized group action - user: ${userLookupService.getUsername(requestingUser)}, action: $userAction, group: ${groupLookupService.getGroupName(groupId)}: Not an admin" }
                ) { "You are not an admin" }

                addUserToGroup(
                    groupId = groupId,
                    memberId = groupMember,
                    timeStamp = now
                )

                // Being in an event's group is what counts as accepting that event, so an admin
                // putting someone in has to write that accept as well - the mirror image of the
                // take-back in REMOVE_USER below.
                eventsLookupService.findByGroupId(groupId)?.let { event ->
                    // An uninvited member cannot even sync the event they now attend, so the add
                    // is also the invite for an invite-only event.
                    val invited = if (event.visibility == EventVisibility.INVITED_FRIENDS_ONLY) {
                        eventsLookupService.addInvitedUser(event.id, groupMember)
                    } else {
                        null
                    }

                    pendingEventUpdate = eventsLookupService.upsertParticipation(
                        eventId = event.id,
                        userId = groupMember,
                        status = EventParticipationStatus.ACCEPTED
                    ) ?: invited
                }

                pendingEvent = SystemEventType.GROUP_MEMBER_ADDED
            }
            GroupMemberAction.REMOVE_USER -> {
                requireOrLog(
                    "remove_group_user_target_not_in_group",
                    groupLookupService.isUserInGroup(groupMember, groupId),
                    { "Group action denied - user: ${userLookupService.getUsername(requestingUser)}, action: $userAction, group: ${groupLookupService.getGroupName(groupId)}, target: ${userLookupService.getUsername(groupMember)}: Target not in group" }
                ) { "User is not in this group" }

                // If someone else is removing the user, they must be admin
                if (requestingUser != groupMember) {
                    requireOrLog(
                        "remove_group_user_not_admin",
                        groupLookupService.isAdmin(requestingUser, groupMembers),
                        { "Unauthorized group action - user: ${userLookupService.getUsername(requestingUser)}, action: $userAction, group: ${groupLookupService.getGroupName(groupId)}: Not an admin" }
                    ) { "You are not an admin" }
                }

                // Being in an event's group is what counts as accepting that event, so leaving it
                // (or being removed) has to take the accept back - otherwise the member shows as
                // going forever, with no way to correct it once the dismiss button is gone.
                // Walking out is a decision, so it reads as DISMISSED; being removed by an admin is
                // not the user's own choice, so that only drops back to SEEN.
                // Runs before the last-member deleteGroup path below, which re-reads the event.
                val statusAfterRemoval = if (requestingUser == groupMember) {
                    EventParticipationStatus.DISMISSED
                } else {
                    EventParticipationStatus.SEEN
                }
                // The creator counts as going for as long as the event exists, so their own entry
                // is never taken back - leaving the chat is not cancelling the event.
                eventsLookupService.findByGroupId(groupId)?.takeIf { groupMember != it.creatorId }?.let { event ->
                    eventsLookupService.setParticipationIfPresent(event.id, groupMember, statusAfterRemoval)?.let { updated ->
                        notificationService.notifyEventUpdate(
                            eventResponse = updated.toResponse(creatorName = userLookupService.getUsername(event.creatorId)),
                            newEntry = false,
                            deleted = false
                        )
                    }
                }

                // If user is leaving and is the last admin, promote someone
                if (requestingUser == groupMember) {
                    if (!groupMembers.any { it.admin && it.userid != requestingUser }) {
                        // Without me, no user is admin
                        val newGroupMembers = groupMembers.filter { it.userid != requestingUser }

                        if (newGroupMembers.isEmpty()) {
                            // Last person leaving - delete the group and all members
                            deleteGroup(groupId, deletedBy = requestingUser)
                            return // Don't update group lastChanged, no system message - no chat left
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
                removedMemberId = groupMember

                pendingEvent = if (requestingUser == groupMember) {
                    SystemEventType.GROUP_MEMBER_LEFT
                } else {
                    SystemEventType.GROUP_MEMBER_REMOVED
                }
            }

            GroupMemberAction.MAKE_ADMIN -> {
                requireOrLog(
                    "make_group_admin_not_admin",
                    groupLookupService.isAdmin(requestingUser, groupMembers),
                    { "Unauthorized group action - user: ${userLookupService.getUsername(requestingUser)}, action: $userAction, group: ${groupLookupService.getGroupName(groupId)}: Not an admin" }
                ) { "You are not an admin" }

                requireOrLog(
                    "make_group_admin_target_not_in_group",
                    groupLookupService.isUserInGroup(groupMember, groupId),
                    { "Group action denied - user: ${userLookupService.getUsername(requestingUser)}, action: $userAction, group: ${groupLookupService.getGroupName(groupId)}, target: ${userLookupService.getUsername(groupMember)}: Target not in group" }
                ) { "User is not in this group" }

                val focusedMember = groupMembers.first { it.userid == groupMember }
                require(!focusedMember.admin) {"User is already admin"}

                groupMemberRepository.save(focusedMember.copy(
                    admin = true
                ))

                pendingEvent = SystemEventType.GROUP_ADMIN_GRANTED
            }
            GroupMemberAction.REMOVE_ADMIN -> {
                requireOrLog(
                    "remove_group_admin_not_admin",
                    groupLookupService.isAdmin(requestingUser, groupMembers),
                    { "Unauthorized group action - user: ${userLookupService.getUsername(requestingUser)}, action: $userAction, group: ${groupLookupService.getGroupName(groupId)}: Not an admin" }
                ) { "You are not an admin" }

                requireOrLog(
                    "remove_group_admin_target_not_in_group",
                    groupLookupService.isUserInGroup(groupMember, groupId),
                    { "Group action denied - user: ${userLookupService.getUsername(requestingUser)}, action: $userAction, group: ${groupLookupService.getGroupName(groupId)}, target: ${userLookupService.getUsername(groupMember)}: Target not in group" }
                ) { "User is not in this group" }

                val focusedMember = groupMembers.first { it.userid == groupMember }
                require(focusedMember.admin) {"User is not an admin"}

                // Check if this is the last admin
                val adminCount = groupMembers.count { it.admin }
                require(adminCount > 1) {"Cannot remove the last admin. Promote someone else first."}

                groupMemberRepository.save(focusedMember.copy(
                    admin = false
                ))

                pendingEvent = SystemEventType.GROUP_ADMIN_REVOKED
            }
        }

        //No error, update group last changed
        groupLookupService.saveGroup(group.copy(updatedAt = now))

        notificationService.notifyGroupUpdate(
            groupResponse = groupLookupService.getGroupAsGroupResponse(groupId),
            deleted = false,
            removedMemberIds = listOfNotNull(removedMemberId),
        )

        pendingEventUpdate?.let { event ->
            notificationService.notifyEventUpdate(
                eventResponse = event.toResponse(creatorName = userLookupService.getUsername(event.creatorId)),
                newEntry = false,
                deleted = false
            )
        }

        pendingEvent.let { eventType ->
            // GROUP_MEMBER_LEFT has no separate target - the actor is the subject of their own event.
            val targets = if (eventType == SystemEventType.GROUP_MEMBER_LEFT) emptyList() else listOf(groupMember)
            systemMessageService.groupEvent(
                groupId = groupId,
                eventType = eventType,
                actorId = requestingUser,
                targets = targets,
            )
        }

    }

    fun addUserToGroup(groupId: ObjectId, memberId: ObjectId, timeStamp: Instant = Clock.System.now()) {
        require(!groupLookupService.isUserInGroup(memberId, groupId)) {"User is already in this group"}

        val groupMembers = groupLookupService.getGroupMembers(groupId)

        val existingColors = groupMembers.map { it.color }.toSet()
        val newColor = ColorGenerator.generateUniqueColorsForGroup(existingColors, 1).first()

        try {
            groupMemberRepository.save(GroupMember(
                userid = memberId,
                groupId = groupId,
                joinedAt = timeStamp,
                admin = false,
                color = newColor,
                joinedAtVersion = versionCounterService.current(SyncCollection.MESSAGES),
            ))
        } catch (e: DuplicateKeyException) {
            throw IllegalArgumentException("User is already in this group")
        }
    }

    /**
     * Bump a group's updatedAt so its sync entry moves to the top for members,
     * for callers that mutate group membership outside [performUserAction] (e.g. event join).
     */
    fun touchGroup(groupId: ObjectId, timeStamp: Instant = Clock.System.now()) {
        val group = groupLookupService.getGroupById(groupId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")

        groupLookupService.saveGroup(group.copy(updatedAt = timeStamp))
    }


    /**
     * User-facing: delete the whole group for every member. Admins only, same as the delete timer.
     * A connected event is detached and survives without a group chat - deleting the event as well
     * stays with its creator in the event screen.
     */
    fun deleteGroupAsAdmin(userId: ObjectId, groupId: ObjectId) {
        groupLookupService.getGroupById(groupId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")

        requireOrLog(
            "delete_group_not_member",
            groupLookupService.isUserInGroup(userId, groupId),
            { "Unauthorized group action - user: ${userLookupService.getUsername(userId)}, action: DELETE_GROUP, group: ${groupLookupService.getGroupName(groupId)}: Not in group" }
        ) { "You are not a member of this group" }
        requireOrLog(
            "delete_group_not_admin",
            groupLookupService.isAdmin(userId, groupLookupService.getGroupMembers(groupId)),
            { "Unauthorized group action - user: ${userLookupService.getUsername(userId)}, action: DELETE_GROUP, group: ${groupLookupService.getGroupName(groupId)}: Not an admin" }
        ) { "You are not an admin" }

        deleteGroup(groupId, deletedBy = userId, deleteConnectedEvent = false)
    }

    /**
     * Soft-delete a group and all of its members. The single point every group deletion goes through,
     * so members are always cleaned up and clients are always notified. The group's messages and their
     * media are hard-deleted. A connected event (if any) is detached (groupId set to null) unless
     * [deleteConnectedEvent] is set, in which case it is deleted too.
     */
    fun deleteGroup(groupId: ObjectId, deletedBy: ObjectId? = null, deleteConnectedEvent: Boolean = false) {
        val group = groupLookupService.getGroupById(groupId) ?: return
        val response = groupLookupService.getGroupAsGroupResponse(groupId)

        groupLookupService.getGroupMembers(groupId).forEach { member ->
            groupMemberRepository.delete(member)
        }

        groupLookupService.saveGroup(group.copy(deleted = true))

        imageManager.deleteProfilePic(groupId.toHexString(), group = true)

        // After the members are gone, so no send can slip in behind the purge
        messageLookupService.deleteAllGroupMessages(groupId)

        loggingService.log(deletedBy, LogType.GROUP_DELETED)

        notificationService.notifyGroupUpdate(response, deleted = true)

        eventsLookupService.findByGroupId(groupId)?.let { event ->
            val creatorName = userLookupService.getUsername(event.creatorId)
            if (deleteConnectedEvent) {
                eventsLookupService.deleteEvent(event)
                notificationService.notifyEventUpdate(
                    eventResponse = event.toResponse(creatorName = creatorName),
                    newEntry = false,
                    deleted = true
                )
            } else {
                val detached = eventsLookupService.save(
                    event.copy(groupId = null, updatedAt = Clock.System.now(), updatedBy = deletedBy ?: event.updatedBy)
                )
                notificationService.notifyEventUpdate(
                    eventResponse = detached.toResponse(creatorName = creatorName),
                    newEntry = false,
                    deleted = false
                )
            }
        }
    }

    /**
     * Permanently deletes every group whose [Group.expiresAt] has passed. The timer of an event
     * group normally fires after the event (close date, or start date, plus the creator's delay),
     * so the event goes with the group. Should the timer ever sit ahead of the event's own end
     * (moved by hand, or the dates were edited apart), the group still goes but the event is only
     * detached - an event that has not happened yet must never be deleted as a side effect of its
     * chat's timer.
     */
    fun deleteExpiredGroups() {
        val now = Clock.System.now()

        groupLookupService.getExpiredGroups(now).forEach { group ->
            val eventEnded = eventsLookupService.findByGroupId(group.id)?.hasEnded(now) ?: true
            deleteGroup(group.id, deletedBy = null, deleteConnectedEvent = eventEnded)
        }
    }


}