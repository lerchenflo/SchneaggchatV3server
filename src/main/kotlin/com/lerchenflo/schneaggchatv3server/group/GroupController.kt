package com.lerchenflo.schneaggchatv3server.group

import com.lerchenflo.schneaggchatv3server.group.model.GroupResponse
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import jakarta.validation.Valid
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/groups")
class GroupController(
    private val groupService: GroupService,
    private val groupLookupService: GroupLookupService,
) {

    @PostMapping("/create", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createGroup(
        @RequestParam("name") groupname: String,
        @RequestParam("memberlist") members: String,
        @RequestParam("description") description: String,
        @RequestParam("profilepic") profilePic: MultipartFile
    ) : GroupResponse {
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        //Members as string that they do not steal all requestparams
        val memberIds = members.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        memberIds.forEach { require(ValidationUtils.validateObjectId(it)) { "Invalid member ID: $it" } }

        val group = groupService.createGroup(
            groupName = groupname,
            members = memberIds.map { ObjectId(it) },
            creatorId = ObjectId(requestingUserId),
            profilePic = profilePic,
            description = description
        )

        return groupLookupService.getGroupAsGroupResponse(group.id)
    }

    @PostMapping("/sync")
    fun syncGroups(
        @RequestBody requestBody: List<UserService.IdTimeStamp>
    ) : GroupService.GroupSyncResponse {
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        return groupService.syncGroups(
            userId = requestingUserId,
            ids = requestBody
        )
    }


    @GetMapping("/profilepic/{id}")
    fun getProfilePic(@PathVariable("id") groupId: String): ResponseEntity<ByteArray> {
        require(ValidationUtils.validateObjectId(groupId)) { "Invalid group ID" }
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        require(groupLookupService.isUserInGroup(ObjectId(requestingUserId), ObjectId(groupId)))

        return groupService.getGroupProfilePic(ObjectId(groupId))
    }

    @PostMapping("/setprofilepic")
    fun setProfilePic(
        @RequestParam("groupid") groupid: String,
        @RequestParam("profilepic") multipartFile: MultipartFile
    ) {
        require(ValidationUtils.validateObjectId(groupid)) { "Invalid group ID" }
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        groupService.changeGroupProfilePic(
            userId = ObjectId(requestingUserId),
            groupId = ObjectId(groupid),
            image = multipartFile
        )
    }


    @PostMapping("/setdescription")
    fun setGroupDescription(
        @RequestParam("groupid") groupid: String,
        @RequestBody newDescription: String
    ) {
        require(ValidationUtils.validateObjectId(groupid)) { "Invalid group ID" }
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        groupService.changeGroupDescription(
            userId = ObjectId(requestingUserId),
            groupId = ObjectId(groupid),
            newDescription = newDescription
        )
    }

    @PostMapping("/setGroupName")
    fun setGroupName(
        @RequestParam("groupid") groupid: String,
        @RequestBody newName: String
    ) {
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        groupService.changeGroupName(
            userId = ObjectId(requestingUserId),
            groupId = ObjectId(groupid),
            newName = newName
        )
    }


    @PostMapping("/changemembers")
    fun changeMembers(
        @Valid @RequestBody groupActionRequest: GroupService.GroupActionRequest
    ){
        require(ValidationUtils.validateObjectId(groupActionRequest.groupMemberId)) { "Invalid group member ID" }
        require(ValidationUtils.validateObjectId(groupActionRequest.groupId)) { "Invalid group ID" }
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        groupService.performUserAction(
            userAction = groupActionRequest.action,
            requestingUser = ObjectId(requestingUserId),
            groupMember = ObjectId(groupActionRequest.groupMemberId),
            groupId = ObjectId(groupActionRequest.groupId)
        )
    }



}