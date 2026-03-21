package com.lerchenflo.schneaggchatv3server.message

import com.lerchenflo.schneaggchatv3server.message.messagemodel.*
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

@RequestMapping("/messages")
@RestController
class MessageController(
    //private val messageRepository: MessageRepository //only use in the messageservice
    private val messageService: MessageService,
) {

    @PostMapping("/send/text")
    fun sendTextMessage(
        @Valid @RequestBody messageRequest: MessageRequest
    ): MessageResponse {
        require(ValidationUtils.validateObjectId(messageRequest.receiverId)) { "Invalid receiver ID" }
        if (messageRequest.messageId != null) require(ValidationUtils.validateObjectId(messageRequest.messageId)) { "Invalid message ID" }
        if (messageRequest.answerId != null) require(ValidationUtils.validateObjectId(messageRequest.answerId)) { "Invalid answer ID" }

        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        if (messageRequest.messageId != null) {
            return messageService.editMessage(ObjectId(messageRequest.messageId), ObjectId(requestingUserId), messageRequest.content)
        }

        //println("Message received: $messageRequest")
        val message = messageService.sendMessage(
            sender = ObjectId(requestingUserId),
            receiver = ObjectId(messageRequest.receiverId),
            groupMessage = messageRequest.groupMessage,
            messageType = MessageType.TEXT,
            content = MessageService.MessageContent.Text(messageRequest.content),
            answerId = if (messageRequest.answerId != null) ObjectId(messageRequest.answerId) else null
        )

        return message.toMessageResponse(ObjectId(requestingUserId))
    }



    @PostMapping("/send/image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun sendImageMessage(
        @RequestPart("image") image: MultipartFile,
        @Valid @RequestPart("request") messageRequest: ImageMessageRequest
    ): MessageResponse {
        require(ValidationUtils.validateObjectId(messageRequest.receiverId)) { "Invalid receiver ID" }
        if (messageRequest.messageId != null) require(ValidationUtils.validateObjectId(messageRequest.messageId)) { "Invalid message ID" }
        if (messageRequest.answerId != null) require(ValidationUtils.validateObjectId(messageRequest.answerId)) { "Invalid answer ID" }

        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        val message = messageService.sendMessage(
            sender = ObjectId(requestingUserId),
            receiver = ObjectId(messageRequest.receiverId),
            groupMessage = messageRequest.groupMessage,
            messageType = MessageType.IMAGE,
            content = MessageService.MessageContent.Image(image, messageRequest.content),
            answerId = if (messageRequest.answerId != null) ObjectId(messageRequest.answerId) else null
        )

        return message.toMessageResponse(ObjectId(requestingUserId))
    }




    @PostMapping("/send/poll")
    fun sendPollMessage(
        @Valid @RequestBody pollMessageRequest: PollMessageRequest
    ): MessageResponse {
        require(ValidationUtils.validateObjectId(pollMessageRequest.receiverId)) { "Invalid receiver ID" }
        if (pollMessageRequest.answerId != null) require(ValidationUtils.validateObjectId(pollMessageRequest.answerId)) { "Invalid answer ID" }
        //TODO: Add poll field validation (title, description, maxAnswers, maxAllowedCustomAnswers, voteOptions count)

        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        //println("Message received: $messageRequest")
        val message = messageService.sendMessage(
            sender = ObjectId(requestingUserId),
            receiver = ObjectId(pollMessageRequest.receiverId),
            groupMessage = pollMessageRequest.groupMessage,
            messageType = MessageType.POLL,
            content = MessageService.MessageContent.Poll(
                poll = pollMessageRequest.poll.toPoll(creatorId = ObjectId(requestingUserId))
            ),
            answerId = if (pollMessageRequest.answerId != null) ObjectId(pollMessageRequest.answerId) else null
        )

        return message.toMessageResponse(ObjectId(requestingUserId))
    }

    @PostMapping("/pollvote")
    fun pollVote(
        @Valid @RequestBody pollVoteRequest: PollVoteRequest
    ): MessageResponse {
        require(ValidationUtils.validateObjectId(pollVoteRequest.messageId)) { "Invalid message ID" }
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        return messageService.votePoll(
            requestingUserId = ObjectId(requestingUserId),
            pollVoteRequest = pollVoteRequest
        ).toMessageResponse(
                requestingUserId = ObjectId(requestingUserId)
            )

    }




    @PostMapping("/sync")
    fun messageSync(
       @RequestParam(value = "page", defaultValue = "0") page: Int,
       @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
       @RequestBody messageRequestList: List<UserService.IdTimeStamp>
    ): MessageService.MessageSyncResponse {
        require(ValidationUtils.validatePaginationPage(page)) { "Invalid page number" }
        require(ValidationUtils.validatePaginationPageSize(pageSize)) { "Invalid page size" }

        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        return messageService.messageSync(
            clientMessages = messageRequestList,
            requestingUser = ObjectId(requestingUserId),
            page = page,
            pageSize = pageSize,
        )

    }

    @PostMapping("/setread")
    fun setMessagesRead(
        @RequestParam(value = "userid") userId: String,
        @RequestParam(value = "group") group: Boolean,
        @RequestParam(value = "timestamp") timestamp: Long

    ){
        require(ValidationUtils.validateObjectId(userId)) { "Invalid user ID" }
        require(ValidationUtils.validateTimestamp(timestamp)) { "Invalid timestamp" }
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        messageService.setMessagesRead(
            ObjectId(requestingUserId), ObjectId(userId),
            group = group,
            timeStamp = timestamp,
        )
    }


    data class EditMessageRequest(
        @field:NotBlank(message = "Message ID must not be blank")
        @field:Size(max = 24, message = "Message ID too long")
        val messageId: String,
        @field:NotBlank(message = "Content must not be blank")
        @field:Size(max = 3000, message = "Content too long")
        val newContent: String,
    )

    @PostMapping("/edit")
    fun editMessage(
        @Valid @RequestBody() editMessageRequest: EditMessageRequest
    ) : MessageResponse {
        require(ValidationUtils.validateObjectId(editMessageRequest.messageId)) { "Invalid message ID" }

        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )
        
        return messageService.editMessage(
            messageId = ObjectId(editMessageRequest.messageId),
            editingUserId = ObjectId(requestingUserId),
            newContent = editMessageRequest.newContent
        )
    }


    @GetMapping("/images/{id}")
    fun getImage(@PathVariable("id") messageId: String): ResponseEntity<ByteArray> {
        require(ValidationUtils.validateObjectId(messageId)) { "Invalid message ID" }
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        val bytearray = messageService.getImageMessage(
            messageId = ObjectId(messageId),
            requestingUserId = ObjectId(requestingUserId)
        )

        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .body(bytearray)
    }


    @DeleteMapping("/delete")
    fun deleteMessage(
        @RequestParam(value = "messageid") messageId: String
    ) {
        require(ValidationUtils.validateObjectId(messageId)) { "Invalid message ID" }
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        messageService.deleteMessage(
            messageId = ObjectId(messageId),
            deletingUserId = ObjectId(requestingUserId),
        )
    }

}