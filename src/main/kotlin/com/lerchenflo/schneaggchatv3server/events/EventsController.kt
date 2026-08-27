package com.lerchenflo.schneaggchatv3server.events

import com.lerchenflo.schneaggchatv3server.core.security.requireAuth
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventJoinRequest
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventJoinResponse
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventRequest
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventResponse
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventSyncResponse
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/events")
class EventsController(
    private val eventService: EventService,
) {

    @PostMapping("/sync")
    fun syncEvents(
        @RequestParam(value = "page", defaultValue = "0") page: Int,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
        @RequestBody requestBody: List<UserService.IdTimeStamp>
    ) : EventSyncResponse {

        require(ValidationUtils.validatePaginationPage(page)) { "Invalid page number" }
        require(ValidationUtils.validatePaginationPageSize(pageSize)) { "Invalid page size" }

        val requestingUserId = requireAuth()

        return eventService.eventIdSync(
            idTimeStamps = requestBody,
            requesterId = requestingUserId,
            page = page,
            pageSize = pageSize
        )
    }

    @PostMapping("/upsert", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upsertEvent(
        @RequestPart("request") requestBody: EventRequest,
        @RequestPart("profilepic", required = false) profilePic: MultipartFile?,
    ): EventResponse {
        requestBody.eventId?.let { eventId ->
            require(ValidationUtils.validateObjectId(requestBody.eventId)) { "Invalid event id" }
        }
        require(ValidationUtils.validateEventTitle(requestBody.title)) { "Invalid event title" }
        require(ValidationUtils.validateEventMaxUsers(requestBody.maxUsers)) { "Invalid max users" }
        // createGroup() skips picture validation when createdFromEvent = true, so it must happen here
        profilePic?.let { require(ValidationUtils.validatePicture(it)) { "Profilepic invalid" } }

        val requestingUserId = requireAuth()

        return eventService.upsertEvent(
            upsertingUser = requestingUserId,
            eventRequest = requestBody,
            profilePic = profilePic,
        )
    }

    @DeleteMapping("/delete")
    fun deleteEvent(
        @RequestParam(value = "eventid") eventId: String,
        @RequestParam(value = "deleteconnectedgroup") deleteConnectedGroup: Boolean,
    ) {
        require(ValidationUtils.validateObjectId(eventId)) { "Invalid event id" }

        val requestingUserId = requireAuth()

        eventService.deleteEvent(
            requestingUser = requestingUserId,
            eventId = eventId,
            deleteConnectedGroup = deleteConnectedGroup
        )
    }

    @PostMapping("/join")
    fun joinEvent(
        @RequestBody requestBody: EventJoinRequest,
    ): EventJoinResponse {
        val requestingUserId = requireAuth()

        return eventService.joinEvent(
            joiningUser = requestingUserId,
            eventJoinRequest = requestBody
        )
    }
}