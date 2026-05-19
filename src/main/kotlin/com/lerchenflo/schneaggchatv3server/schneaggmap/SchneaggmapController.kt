package com.lerchenflo.schneaggchatv3server.schneaggmap

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeValue
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MainType
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MainTypeResponse
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntryResponse
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.SubtypeResponse
import com.lerchenflo.schneaggchatv3server.schneaggmap.SubtypeSyncResponse
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.toMainTypeResponse
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.toMapEntryResponse
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/map")
class SchneaggmapController(
    private val schneaggmapService: SchneaggmapService,
) {

    data class MapEntryRequest(
        @field:NotBlank(message = "Main type key must not be blank")
        @field:Size(max = 50, message = "Main type key too long")
        val mainTypeKey: String,
        val subtypeIds: List<String>,
        val coordinates: LatLong,
        @field:Size(max = 500, message = "Description too long")
        val description: String,
        val attributes: Map<String, AttributeValue>,
    )

    data class EditMapEntryRequest(
        @field:NotBlank(message = "Entry ID must not be blank")
        @field:Size(max = 24, message = "Entry ID too long")
        val entryId: String,
        val subtypeIds: List<String>,
        val coordinates: LatLong,
        @field:Size(max = 500, message = "Description too long")
        val description: String,
        val attributes: Map<String, AttributeValue>,
    )

    data class SubtypeCreateRequest(
        @field:NotBlank(message = "Main type key must not be blank")
        @field:Size(max = 50, message = "Main type key too long")
        val mainTypeKey: String,
        @field:NotBlank(message = "Name must not be blank")
        @field:Size(max = 50, message = "Name too long")
        val name: String,
    )

    @PostMapping("/create")
    fun createMapEntry(@Valid @RequestBody request: MapEntryRequest): MapEntryResponse {
        val requesterId = requesterId()
        require(request.subtypeIds.all { ValidationUtils.validateObjectId(it) }) { "Invalid subtype ID" }
        return schneaggmapService.createMapEntry(
            mainTypeKey = request.mainTypeKey,
            subtypeIdStrings = request.subtypeIds,
            coordinates = request.coordinates,
            description = request.description,
            attributes = request.attributes,
            requesterId = requesterId,
        ).toMapEntryResponse()
    }

    @PostMapping("/edit")
    fun editMapEntry(@Valid @RequestBody request: EditMapEntryRequest): MapEntryResponse {
        val requesterId = requesterId()
        require(ValidationUtils.validateObjectId(request.entryId)) { "Invalid entry ID" }
        require(request.subtypeIds.all { ValidationUtils.validateObjectId(it) }) { "Invalid subtype ID" }
        return schneaggmapService.editMapEntry(
            entryId = ObjectId(request.entryId),
            subtypeIdStrings = request.subtypeIds,
            coordinates = request.coordinates,
            description = request.description,
            attributes = request.attributes,
            requesterId = requesterId,
        ).toMapEntryResponse()
    }

    @DeleteMapping("/delete")
    fun deleteMapEntry(@RequestParam entryid: String) {
        val requesterId = requesterId()
        require(ValidationUtils.validateObjectId(entryid)) { "Invalid entry ID" }
        schneaggmapService.deleteMapEntry(ObjectId(entryid), requesterId)
    }

    @PostMapping("/sync")
    fun syncMapEntries(
        @RequestParam(value = "page", defaultValue = "0") page: Int,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
        @RequestBody clientEntries: List<UserService.IdTimeStamp>,
    ): MapSyncResponse {
        require(ValidationUtils.validatePaginationPage(page)) { "Invalid page number" }
        require(ValidationUtils.validatePaginationPageSize(pageSize)) { "Invalid page size" }
        requesterId()
        return schneaggmapService.mapSync(clientEntries, page, pageSize)
    }

    @GetMapping("/maintypes")
    fun getMainTypes(): List<MainTypeResponse> {
        requesterId()
        return MainType.entries.map { it.toMainTypeResponse() }
    }

    @GetMapping("/subtypes/{mainTypeKey}")
    fun getSubtypes(@PathVariable mainTypeKey: String): List<SubtypeResponse> {
        requesterId()
        return schneaggmapService.listSubtypes(mainTypeKey)
    }

    @PostMapping("/subtypes/create")
    fun createSubtype(@Valid @RequestBody request: SubtypeCreateRequest): SubtypeResponse {
        val requesterId = requesterId()
        return schneaggmapService.createSubtype(request.mainTypeKey, request.name, requesterId)
    }

    @PostMapping("/subtypes/sync")
    fun syncSubtypes(
        @RequestParam(value = "page", defaultValue = "0") page: Int,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
        @RequestBody clientEntries: List<UserService.IdTimeStamp>,
    ): SubtypeSyncResponse {
        require(ValidationUtils.validatePaginationPage(page)) { "Invalid page number" }
        require(ValidationUtils.validatePaginationPageSize(pageSize)) { "Invalid page size" }
        requesterId()
        return schneaggmapService.subtypeSync(clientEntries, page, pageSize)
    }

    private fun requesterId(): ObjectId {
        val id = SecurityContextHolder.getContext().authentication?.principal as? String
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not logged in")
        return ObjectId(id)
    }
}
