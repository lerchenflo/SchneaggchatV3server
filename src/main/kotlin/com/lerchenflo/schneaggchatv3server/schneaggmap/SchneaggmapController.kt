package com.lerchenflo.schneaggchatv3server.schneaggmap

import com.lerchenflo.schneaggchatv3server.core.security.requireAuth
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.*
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/map")
class SchneaggmapController(
    private val schneaggmapService: SchneaggmapService,
) {

    data class MapEntryRequest(
        @field:NotBlank(message = "Name must not be blank")
        @field:Size(max = 100, message = "Name too long")
        val name: String,
        @field:Size(max = 500, message = "Description too long")
        val description: String,
        val coordinates: LatLong,
        val locationData: LocationData,
    )

    data class EditMapEntryRequest(
        @field:NotBlank(message = "Entry ID must not be blank")
        val entryId: String,
        @field:NotBlank(message = "Name must not be blank")
        @field:Size(max = 100, message = "Name too long")
        val name: String,
        @field:Size(max = 500, message = "Description too long")
        val description: String,
        val coordinates: LatLong,
        val locationData: LocationData,
    )

    @PostMapping("/create")
    fun createMapEntry(@Valid @RequestBody request: MapEntryRequest): MapEntryResponse {
        val requesterId = requireAuth()
        return schneaggmapService.createMapEntry(
            name         = request.name,
            description  = request.description,
            coordinates  = request.coordinates,
            locationData = request.locationData,
            requesterId  = requesterId,
        ).toMapEntryResponse()
    }

    @PostMapping("/edit")
    fun editMapEntry(@Valid @RequestBody request: EditMapEntryRequest): MapEntryResponse {
        val requesterId = requireAuth()
        require(ValidationUtils.validateObjectId(request.entryId)) { "Invalid entry ID" }
        return schneaggmapService.editMapEntry(
            entryId      = ObjectId(request.entryId),
            name         = request.name,
            description  = request.description,
            coordinates  = request.coordinates,
            locationData = request.locationData,
            requesterId  = requesterId,
        ).toMapEntryResponse()
    }

    @DeleteMapping("/delete")
    fun deleteMapEntry(@RequestParam entryid: String) {
        val requesterId = requireAuth()
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
        requireAuth()
        return schneaggmapService.mapSync(clientEntries, page, pageSize)
    }

}
