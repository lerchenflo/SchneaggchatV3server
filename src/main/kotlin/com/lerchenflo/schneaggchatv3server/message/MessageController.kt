package com.lerchenflo.schneaggchatv3server.message

import com.lerchenflo.schneaggchatv3server.core.security.requireAuth
import com.lerchenflo.schneaggchatv3server.message.messagemodel.*
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.bson.types.ObjectId
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

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
        require(ValidationUtils.validateClientMessageId(messageRequest.clientMessageId)) { "Invalid client message ID" }

        val requestingUserId = requireAuth()

        if (messageRequest.messageId != null) {
            return messageService.editMessage(ObjectId(messageRequest.messageId), requestingUserId, messageRequest.content)
        }

        //println("Message received: $messageRequest")
        val message = messageService.sendMessage(
            sender = requestingUserId,
            receiver = ObjectId(messageRequest.receiverId),
            groupMessage = messageRequest.groupMessage,
            messageType = MessageType.TEXT,
            content = MessageService.MessageContent.Text(messageRequest.content),
            answerId = if (messageRequest.answerId != null) ObjectId(messageRequest.answerId) else null,
            clientMessageId = messageRequest.clientMessageId,
        )

        return message.toMessageResponse(requestingUserId)
    }



    @PostMapping("/send/image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun sendImageMessage(
        @RequestPart("image") image: MultipartFile,
        @Valid @RequestPart("request") messageRequest: ImageMessageRequest
    ): MessageResponse {
        require(ValidationUtils.validateObjectId(messageRequest.receiverId)) { "Invalid receiver ID" }
        if (messageRequest.messageId != null) require(ValidationUtils.validateObjectId(messageRequest.messageId)) { "Invalid message ID" }
        if (messageRequest.answerId != null) require(ValidationUtils.validateObjectId(messageRequest.answerId)) { "Invalid answer ID" }
        require(ValidationUtils.validateClientMessageId(messageRequest.clientMessageId)) { "Invalid client message ID" }

        val requestingUserId = requireAuth()

        val message = messageService.sendMessage(
            sender = requestingUserId,
            receiver = ObjectId(messageRequest.receiverId),
            groupMessage = messageRequest.groupMessage,
            messageType = MessageType.IMAGE,
            content = MessageService.MessageContent.Image(image, messageRequest.content),
            answerId = if (messageRequest.answerId != null) ObjectId(messageRequest.answerId) else null,
            clientMessageId = messageRequest.clientMessageId,
        )

        return message.toMessageResponse(requestingUserId)
    }


    @PostMapping("/send/audio", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun sendAudioMessage(
        @RequestPart("audio") audio: MultipartFile,
        @Valid @RequestPart("request") messageRequest: AudioMessageRequest
    ): MessageResponse {
        require(ValidationUtils.validateObjectId(messageRequest.receiverId)) { "Invalid receiver ID" }
        if (messageRequest.messageId != null) require(ValidationUtils.validateObjectId(messageRequest.messageId)) { "Invalid message ID" }
        if (messageRequest.answerId != null) require(ValidationUtils.validateObjectId(messageRequest.answerId)) { "Invalid answer ID" }
        require(ValidationUtils.validateClientMessageId(messageRequest.clientMessageId)) { "Invalid client message ID" }

        val requestingUserId = requireAuth()

        val message = messageService.sendMessage(
            sender = requestingUserId,
            receiver = ObjectId(messageRequest.receiverId),
            groupMessage = messageRequest.groupMessage,
            messageType = MessageType.AUDIO,
            content = MessageService.MessageContent.Audio(audio),
            answerId = if (messageRequest.answerId != null) ObjectId(messageRequest.answerId) else null,
            clientMessageId = messageRequest.clientMessageId,
        )

        return message.toMessageResponse(requestingUserId)
    }


    @PostMapping("/send/poll")
    fun sendPollMessage(
        @Valid @RequestBody pollMessageRequest: PollMessageRequest
    ): MessageResponse {
        require(ValidationUtils.validateObjectId(pollMessageRequest.receiverId)) { "Invalid receiver ID" }
        if (pollMessageRequest.answerId != null) require(ValidationUtils.validateObjectId(pollMessageRequest.answerId)) { "Invalid answer ID" }
        require(ValidationUtils.validateClientMessageId(pollMessageRequest.clientMessageId)) { "Invalid client message ID" }

        val requestingUserId = requireAuth()

        //println("Message received: $messageRequest")
        val message = messageService.sendMessage(
            sender = requestingUserId,
            receiver = ObjectId(pollMessageRequest.receiverId),
            groupMessage = pollMessageRequest.groupMessage,
            messageType = MessageType.POLL,
            content = MessageService.MessageContent.Poll(
                poll = pollMessageRequest.poll.toPoll(creatorId = requestingUserId)
            ),
            answerId = if (pollMessageRequest.answerId != null) ObjectId(pollMessageRequest.answerId) else null,
            clientMessageId = pollMessageRequest.clientMessageId,
        )

        return message.toMessageResponse(requestingUserId)
    }

    @PostMapping("/pollvote")
    fun pollVote(
        @Valid @RequestBody pollVoteRequest: PollVoteRequest
    ): MessageResponse {
        require(ValidationUtils.validateObjectId(pollVoteRequest.messageId)) { "Invalid message ID" }
        val requestingUserId = requireAuth()

        return messageService.votePoll(
            requestingUserId = requestingUserId,
            pollVoteRequest = pollVoteRequest
        ).toMessageResponse(
                requestingUserId = requestingUserId
            )

    }

    @PostMapping("/polloption/delete")
    fun deletePollOption(
        @Valid @RequestBody request: PollOptionDeleteRequest
    ): MessageResponse {
        require(ValidationUtils.validateObjectId(request.messageId)) { "Invalid message ID" }
        require(ValidationUtils.validateObjectId(request.optionId)) { "Invalid option ID" }
        val requestingUserId = requireAuth()

        return messageService.deletePollOption(
            requestingUserId = requestingUserId,
            request = request
        ).toMessageResponse(requestingUserId = requestingUserId)
    }




    /**
     * Version-based incremental sync: the client sends only the highest `version` it has already
     * seen (0 on first sync), and gets back everything newer plus the ids of anything deleted since.
     * Replaces the old id/timestamp-list based sync - see docs/CLIENT_SYNC_MIGRATION.md.
     */
    @GetMapping("/sync")
    fun messageSync(
       @RequestParam(value = "since", defaultValue = "0") since: Long,
       @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
    ): MessageService.MessageSyncResponse {
        require(ValidationUtils.validateSyncVersion(since)) { "Invalid since version" }
        require(ValidationUtils.validatePaginationPageSize(pageSize)) { "Invalid page size" }

        val requestingUserId = requireAuth()

        return messageService.messageSync(
            since = since,
            requestingUser = requestingUserId,
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
        val requestingUserId = requireAuth()


        messageService.setMessagesRead(
            requestingUserId, ObjectId(userId),
            group = group,
            timeStamp = timestamp,
        )
    }


    data class EditMessageRequest(
        @field:NotBlank(message = "Message ID must not be blank")
        @field:Size(max = 24, message = "Message ID too long")
        val messageId: String,
        @field:NotBlank(message = "Content must not be blank")
        @field:Size(max = 10000, message = "Content too long (max 10000)")
        val newContent: String,
    )

    @PostMapping("/edit")
    fun editMessage(
        @Valid @RequestBody() editMessageRequest: EditMessageRequest
    ) : MessageResponse {
        require(ValidationUtils.validateObjectId(editMessageRequest.messageId)) { "Invalid message ID" }

        val requestingUserId = requireAuth()

        return messageService.editMessage(
            messageId = ObjectId(editMessageRequest.messageId),
            editingUserId = requestingUserId,
            newContent = editMessageRequest.newContent
        )
    }


    @PostMapping("/react")
    fun reactToMessage(
        @Valid @RequestBody reactionRequest: ReactionRequest
    ): MessageResponse {
        require(ValidationUtils.validateObjectId(reactionRequest.messageId)) { "Invalid message ID" }

        val requestingUserId = requireAuth()


        return messageService.reactToMessage(
            messageId = ObjectId(reactionRequest.messageId),
            reactingUserId = requestingUserId,
            content = reactionRequest.content,
        )
    }


    @GetMapping("/images/{id}")
    fun getImage(@PathVariable("id") messageId: String): ResponseEntity<ByteArray> {
        require(ValidationUtils.validateObjectId(messageId)) { "Invalid message ID" }
        val requestingUserId = requireAuth()

        val bytearray = messageService.getImageMessage(
            messageId = ObjectId(messageId),
            requestingUserId = requestingUserId
        )

        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .body(bytearray)
    }

    @GetMapping("/audios/{id}")
    fun getAudio(@PathVariable("id") messageId: String): ResponseEntity<ByteArray> {
        val requestingUserId = requireAuth()

        val bytearray = messageService.getAudioMessage(
            messageId = ObjectId(messageId),
            requestingUserId = requestingUserId
        )

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("audio/mp4"))
            .body(bytearray)
    }


    @DeleteMapping("/delete")
    fun deleteMessage(
        @RequestParam(value = "messageid") messageId: String
    ) {
        require(ValidationUtils.validateObjectId(messageId)) { "Invalid message ID" }
        val requestingUserId = requireAuth()

        messageService.deleteMessage(
            messageId = ObjectId(messageId),
            deletingUserId = requestingUserId,
        )
    }

}