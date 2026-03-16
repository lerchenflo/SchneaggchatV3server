package com.lerchenflo.schneaggchatv3server.message

import com.lerchenflo.schneaggchatv3server.message.messagemodel.AudioMessageRequest
import com.lerchenflo.schneaggchatv3server.message.messagemodel.ImageMessageRequest
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageRequest
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageResponse
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageType
import com.lerchenflo.schneaggchatv3server.message.messagemodel.PollMessageRequest
import com.lerchenflo.schneaggchatv3server.message.messagemodel.PollVoteRequest
import com.lerchenflo.schneaggchatv3server.message.messagemodel.toMessageResponse
import com.lerchenflo.schneaggchatv3server.message.messagemodel.toPoll
import com.lerchenflo.schneaggchatv3server.user.UserController
import com.lerchenflo.schneaggchatv3server.user.UserService
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
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
        @RequestBody messageRequest: MessageRequest
    ): MessageResponse {

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
        @RequestPart("request") messageRequest: ImageMessageRequest
    ): MessageResponse {
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


    @PostMapping("/send/audio", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun sendAudioMessage(
        @RequestPart("audio") audio: MultipartFile,
        @RequestPart("request") messageRequest: AudioMessageRequest
    ): MessageResponse {
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        val message = messageService.sendMessage(
            sender = ObjectId(requestingUserId),
            receiver = ObjectId(messageRequest.receiverId),
            groupMessage = messageRequest.groupMessage,
            messageType = MessageType.AUDIO,
            content = MessageService.MessageContent.Audio(audio),
            answerId = if (messageRequest.answerId != null) ObjectId(messageRequest.answerId) else null
        )

        return message.toMessageResponse(ObjectId(requestingUserId))
    }


    @PostMapping("/send/poll")
    fun sendPollMessage(
        @RequestBody pollMessageRequest: PollMessageRequest
    ): MessageResponse {

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
        @RequestBody pollVoteRequest: PollVoteRequest
    ): MessageResponse {
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
        val messageId: String,
        val newContent: String,
    )

    @PostMapping("/edit")
    fun editMessage(
        @RequestBody() editMessageRequest: EditMessageRequest
    ) : MessageResponse {

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

    @GetMapping("/audios/{id}")
    fun getAudio(@PathVariable("id") messageId: String): ResponseEntity<ByteArray> {
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        val bytearray = messageService.getAudioMessage(
            messageId = ObjectId(messageId),
            requestingUserId = ObjectId(requestingUserId)
        )

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("audio/mp4"))
            .body(bytearray)
    }


    @DeleteMapping("/delete")
    fun deleteMessage(
        @RequestParam(value = "messageid") messageId: String
    ) {
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