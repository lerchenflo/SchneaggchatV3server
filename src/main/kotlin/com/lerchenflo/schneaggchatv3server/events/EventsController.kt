package com.lerchenflo.schneaggchatv3server.events

import com.lerchenflo.schneaggchatv3server.core.security.requireAuth
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventJoinRequest
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventRequest
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventResponse
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventSyncResponse
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

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

    @PostMapping("/upsert")
    fun upsertEvent(
        @RequestBody requestBody: EventRequest,
    ): EventResponse {
        val requestingUserId = requireAuth()

        return eventService.upsertEvent(
            upsertingUser = requestingUserId,
            eventRequest = requestBody
        )
    }

    @PostMapping("/join")
    fun joinEvent(
        @RequestBody requestBody: EventJoinRequest,
    ) {
        val requestingUserId = requireAuth()

        eventService.joinEvent(
            joiningUser = requestingUserId,
            eventJoinRequest = requestBody
        )
    }
}