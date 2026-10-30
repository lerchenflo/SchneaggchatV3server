package com.lerchenflo.schneaggchatv3server.group.groupextensions

import com.lerchenflo.schneaggchatv3server.core.security.requireAuth
import com.lerchenflo.schneaggchatv3server.group.groupextensions.model.*
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/groupextensions")
class GroupExtensionController(
    private val groupExtensionService: GroupExtensionService,
) {

    data class ExtensionRequest(
        val extensionId: String?,
        val ownerType: ExtensionOwnerType,
        val ownerId: String,
        val type: GroupExtensionType,
        @field:NotBlank(message = "Title must not be blank")
        @field:Size(max = 100, message = "Title too long")
        val title: String,
        val settings: ExtensionSettings,
    )

    data class ItemRequest(
        val extensionId: String,
        val itemId: String?,
        val data: ExtensionItemData,
    )


    @PostMapping("/upsert")
    fun upsertExtension(@Valid @RequestBody request: ExtensionRequest): GroupExtensionResponse {
        val requesterId = requireAuth()
        request.extensionId?.let { require(ValidationUtils.validateObjectId(it)) { "Invalid extension ID" } }
        require(ValidationUtils.validateObjectId(request.ownerId)) { "Invalid owner ID" }

        return groupExtensionService.upsertExtension(
            extensionId = request.extensionId?.let { ObjectId(it) },
            ownerType   = request.ownerType,
            ownerId     = ObjectId(request.ownerId),
            type        = request.type,
            title       = request.title.trim(),
            settings    = request.settings,
            requesterId = requesterId,
        ).toGroupExtensionResponse()
    }

    @DeleteMapping("/delete")
    fun deleteExtension(@RequestParam extensionid: String) {
        val requesterId = requireAuth()
        require(ValidationUtils.validateObjectId(extensionid)) { "Invalid extension ID" }
        groupExtensionService.deleteExtension(ObjectId(extensionid), requesterId)
    }

    @PostMapping("/items/upsert")
    fun upsertItem(@RequestBody request: ItemRequest): GroupExtensionResponse {
        val requesterId = requireAuth()
        require(ValidationUtils.validateObjectId(request.extensionId)) { "Invalid extension ID" }
        request.itemId?.let { require(ValidationUtils.validateObjectId(it)) { "Invalid item ID" } }

        return groupExtensionService.upsertItem(
            extensionId = ObjectId(request.extensionId),
            itemId      = request.itemId?.let { ObjectId(it) },
            data        = request.data,
            requesterId = requesterId,
        ).toGroupExtensionResponse()
    }

    @DeleteMapping("/items/delete")
    fun deleteItem(@RequestParam extensionid: String, @RequestParam itemid: String): GroupExtensionResponse {
        val requesterId = requireAuth()
        require(ValidationUtils.validateObjectId(extensionid)) { "Invalid extension ID" }
        require(ValidationUtils.validateObjectId(itemid)) { "Invalid item ID" }
        return groupExtensionService.deleteItem(ObjectId(extensionid), ObjectId(itemid), requesterId)
            .toGroupExtensionResponse()
    }

    @PostMapping("/sync")
    fun syncExtensions(
        @RequestParam(value = "page", defaultValue = "0") page: Int,
        @RequestParam(value = "page_size", defaultValue = "100") pageSize: Int,
        @RequestBody clientEntries: List<UserService.IdTimeStamp>,
    ): GroupExtensionSyncResponse {
        val requesterId = requireAuth()
        require(ValidationUtils.validatePaginationPage(page)) { "Invalid page number" }
        require(ValidationUtils.validatePaginationPageSize(pageSize)) { "Invalid page size" }
        require(clientEntries.size <= 10_000) { "Too many sync entries" }
        return groupExtensionService.extensionSync(clientEntries, page, pageSize, requesterId)
    }
}
