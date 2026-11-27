@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.group

import com.lerchenflo.schneaggchatv3server.core.security.requireAuth
import com.lerchenflo.schneaggchatv3server.group.model.GroupResponse
import com.lerchenflo.schneaggchatv3server.group.model.GroupSettingsRequest
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import com.lerchenflo.schneaggchatv3server.util.requireOrLog
import jakarta.validation.Valid
import org.bson.types.ObjectId
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import kotlin.time.ExperimentalTime

@RestController
@RequestMapping("/groups")
class GroupController(
    private val groupService: GroupService,
    private val groupLookupService: GroupLookupService,
    private val userLookupService: UserLookupService,
) {

    @PostMapping("/create", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createGroup(
        @RequestParam("name") groupname: String,
        @RequestParam("memberlist") members: String,
        @RequestParam("description") description: String,
        @RequestParam("profilepic") profilePic: MultipartFile,
        /** Required, "true" / "false". See Group.membersCanAdd. */
        @RequestParam("membersCanAdd") membersCanAdd: Boolean,
    ) : GroupResponse {
        val requestingUserId = requireAuth()

        //Members as string that they do not steal all requestparams
        val memberIds = members.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        memberIds.forEach { require(ValidationUtils.validateObjectId(it)) { "Invalid member ID: $it" } }

        val group = groupService.createGroup(
            groupName = groupname,
            members = memberIds.map { ObjectId(it) },
            creatorId = requestingUserId,
            profilePic = profilePic,
            description = description,
            createdFromEvent = false,
            membersCanAdd = membersCanAdd,
        )

        return groupLookupService.getGroupAsGroupResponse(group.id)
    }

    @PostMapping("/sync")
    fun syncGroups(
        @RequestBody requestBody: List<UserService.IdTimeStamp>
    ) : GroupService.GroupSyncResponse {
        val requestingUserId = requireAuth()

        return groupService.syncGroups(
            userId = requestingUserId,
            ids = requestBody
        )
    }


    @GetMapping("/profilepic/{id}")
    fun getProfilePic(@PathVariable("id") groupId: String): ResponseEntity<ByteArray> {
        require(ValidationUtils.validateObjectId(groupId)) { "Invalid group ID" }
        val requestingUserId = requireAuth()


        requireOrLog(
            "get_group_profilepic_not_member",
            groupLookupService.isUserInGroup(requestingUserId, ObjectId(groupId)),
            { "Unauthorized group action - user: ${userLookupService.getUsername(requestingUserId)}, action: GET_PROFILE_PIC, group: ${groupLookupService.getGroupName(ObjectId(groupId))}: Not in group" }
        ) { "You are not a member of this group" }

        return groupService.getGroupProfilePic(ObjectId(groupId))
    }

    @PostMapping("/setprofilepic")
    fun setProfilePic(
        @RequestParam("groupid") groupid: String,
        @RequestParam("profilepic") multipartFile: MultipartFile
    ) {
        require(ValidationUtils.validateObjectId(groupid)) { "Invalid group ID" }
        val requestingUserId = requireAuth()


        groupService.changeGroupProfilePic(
            userId = requestingUserId,
            groupId = ObjectId(groupid),
            image = multipartFile
        )
    }


    /**
     * Partial update of a group's settings, like `/users/settings`: a null field is left as it is.
     * Name and description: every member. Delete timer and [GroupSettingsRequest.membersCanAdd]:
     * admins only. All checks run before anything is written, so a rejected request changes nothing.
     */
    @PostMapping("/settings")
    fun updateGroupSettings(
        @Valid @RequestBody request: GroupSettingsRequest
    ) {
        require(ValidationUtils.validateObjectId(request.groupId)) { "Invalid group ID" }
        val requestingUserId = requireAuth()

        groupService.updateGroupSettings(
            userId = requestingUserId,
            groupId = ObjectId(request.groupId),
            request = request,
        )
    }


    @DeleteMapping("/delete")
    fun deleteGroup(
        @RequestParam("groupid") groupid: String
    ) {
        require(ValidationUtils.validateObjectId(groupid)) { "Invalid group ID" }
        val requestingUserId = requireAuth()

        groupService.deleteGroupAsAdmin(
            userId = requestingUserId,
            groupId = ObjectId(groupid)
        )
    }


    @PostMapping("/changemembers")
    fun changeMembers(
        @Valid @RequestBody groupActionRequest: GroupService.GroupActionRequest
    ){
        require(ValidationUtils.validateObjectId(groupActionRequest.groupMemberId)) { "Invalid group member ID" }
        require(ValidationUtils.validateObjectId(groupActionRequest.groupId)) { "Invalid group ID" }
        val requestingUserId = requireAuth()


        groupService.performUserAction(
            userAction = groupActionRequest.action,
            requestingUser = requestingUserId,
            groupMember = ObjectId(groupActionRequest.groupMemberId),
            groupId = ObjectId(groupActionRequest.groupId)
        )
    }



}