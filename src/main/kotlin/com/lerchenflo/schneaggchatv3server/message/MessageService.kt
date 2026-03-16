@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.message

import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.group.GroupService
import com.lerchenflo.schneaggchatv3server.message.MessageService.MessageContent.Image
import com.lerchenflo.schneaggchatv3server.message.MessageService.MessageContent.Text
import com.lerchenflo.schneaggchatv3server.message.messagemodel.*
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.repository.MessageRepository
import com.lerchenflo.schneaggchatv3server.user.FriendsService
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.AudioManager
import com.lerchenflo.schneaggchatv3server.util.ImageManager
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import com.lerchenflo.schneaggchatv3server.util.withOptimisticRetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bson.types.ObjectId
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.find
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Component
class MessageService(
    private val mongoTemplate: MongoTemplate,
    private val messageRepository: MessageRepository,
    private val friendsService: FriendsService,
    private val groupLookupService: GroupLookupService,
    private val imageManager: ImageManager,
    private val audioManager: AudioManager,
    private val notificationService: NotificationService,
    private val loggingService: LoggingService
) {

    sealed class MessageContent {
        data class Text(val message: String) : MessageContent()
        data class Image(val image: MultipartFile, val text: String) : MessageContent()
        data class Audio(val audio: MultipartFile) : MessageContent()

        data class Poll(val poll: PollMessage) : MessageContent()
    }

    fun sendMessage(sender: ObjectId, receiver: ObjectId, groupMessage: Boolean, messageType: MessageType, content: MessageContent, answerId: ObjectId?) : Message {

        canUserAccessMessage(
            sender = sender,
            receiver = receiver,
            groupMessage = groupMessage,
        )


        when (messageType) {
            MessageType.TEXT -> {
                require(content is Text) { "Message type must be Text" }

                require(ValidationUtils.validateStringMessage(content.message)) { "Invalid text message" }
            }
            MessageType.IMAGE -> {

                require(content is Image) { "Image message type must be Image" }

                if (content.text.isNotEmpty()) {
                    require(ValidationUtils.validateStringMessage(content.text)) { "Invalid text message" }
                }

                //TODO Image validation
            }
            MessageType.POLL -> {
                require(content is MessageContent.Poll) { "Pollmessage with empty poll" }

                //TODO: Poll validation
                if (content.poll.closeDate != null) {
                    require(content.poll.closeDate > Clock.System.now()) { "Poll closedate is in the past" }
                }

                content.poll.voteOptions.forEach { voteOption ->
                    require(ValidationUtils.validatePollVoteText(voteOption.text)) {"Pollvote option text in wrong format"}
                }
            }
            MessageType.AUDIO -> {

                require(content is MessageContent.Audio) { "Audio message type must be Audio" }

                /*
                if (content.text.isNotEmpty()) {
                    require(ValidationUtils.validateStringMessage(content.text)) { "Invalid text message" }
                }

                 */

                //TODO Audio validation
            }
        }



        val savedObjectId = ObjectId()

        val storedContent = when(content) {
            is Image -> {
                //Save image to file
                imageManager.saveImageMessage(
                    image = content.image,
                    messageId = savedObjectId,
                    group = groupMessage
                )

                //Save the text as content
                content.text
            }
            is MessageContent.Audio -> {
                //Save image to file
                audioManager.saveAudioMessage(
                    audio = content.audio,
                    messageId = savedObjectId,
                    group = groupMessage
                )

                //Save the text as content
                //content.text
            }
            is Text -> {
                content.message
            }

            is MessageContent.Poll -> {
                ""
            }
        }


        val sendDate = Clock.System.now()

        val message = messageRepository.save(Message(
            id = savedObjectId,
            senderId = sender,
            receiverId = receiver,
            groupMessage = groupMessage,
            msgType = messageType,
            content = storedContent,
            poll = if (content is MessageContent.Poll) content.poll else null,
            answerId = answerId,
            sendDate = sendDate,
            lastChanged = sendDate,
            deleted = false,
            readers = listOf(Reader(
                userId = sender,
                readAt = sendDate
            )),
        ))


        notificationService.notifyMessageUpdate(
            message = message,
            newMessage = true,
            deleted = false,
            changingUserId = sender
        )


        return message
    }


    fun votePoll(requestingUserId: ObjectId, pollVoteRequest: PollVoteRequest) : Message {
        return withOptimisticRetry {
            val message = canUserAccessMessage(
                messageId = ObjectId(pollVoteRequest.messageId),
                userId = requestingUserId
            )

            //Throw if the message is not a poll
            require(message.msgType == MessageType.POLL && message.poll != null) { "This is not a poll message" }

            //Validate pollrequest

            //Create a new option
            if (pollVoteRequest.id == null) {
                require(pollVoteRequest.text != null && ValidationUtils.validatePollVoteText(pollVoteRequest.text)) { "Poll text invalid" }
            }


            var poll = message.poll

            val timeStamp = Clock.System.now()

            //Block custom answers if not allowed
            if (pollVoteRequest.id == null || poll.voteOptions.none { it.id == pollVoteRequest.id }) {
                require(poll.customAnswersEnabled) { "Custom answers are not allowed for this poll" }
            }

            //Block answers after expiry
            if (poll.closeDate != null) {
                require(Clock.System.now() < poll.closeDate) { "Poll is closed" }
            }

            //Check max answer limit for this poll
            if (poll.maxAnswers != null) {
                val thisUserVoteCount = poll.getVoteCountForUser(requestingUserId)

                if (pollVoteRequest.selected && thisUserVoteCount >= poll.maxAnswers) {
                    val oldestVote = poll.voteOptions
                        .flatMap { option -> option.voters.map { voter -> option to voter } }
                        .filter { (_, voter) -> voter.userId == requestingUserId }
                        .minByOrNull { (_, voter) -> voter.votedAt }

                    if (oldestVote != null) {
                        val (optionToModify, voterToRemove) = oldestVote
                        poll = poll.copy(
                            voteOptions = poll.voteOptions.map { option ->
                                if (option.id == optionToModify.id) {
                                    option.copy(voters = option.voters - voterToRemove)
                                } else {
                                    option
                                }
                            }
                        )
                    }
                }
            }

            //Ready for poll voting, no illegal states

            if (pollVoteRequest.id == null) {

                //Check if user created the max allowed custom answers
                val userCreatedCustomPollCount = poll.getCustomVoteCountForUser(requestingUserId)

                //Not unlimited custom answers allowed
                if (poll.maxAllowedCustomAnswers != null) {
                    require(userCreatedCustomPollCount < poll.maxAllowedCustomAnswers) { "You already made the max amount of custom answers allowed" }
                }


                //User created a new option
                poll = poll.copy(
                    voteOptions = poll.voteOptions + PollVoteOption(
                        id = ObjectId.get().toHexString(),
                        text = pollVoteRequest.text!!,
                        custom = true,
                        creatorId = requestingUserId,

                        //User automatically votes for his created item
                        voters = listOf(PollVoter(
                            userId = requestingUserId,
                            votedAt = timeStamp
                        ))
                    )
                )
            } else {

                //Option selected
                poll = poll.copy(
                    voteOptions = poll.voteOptions.map { option ->
                        if (option.id == pollVoteRequest.id) {

                            //User selected this option, add him as voter
                            if (pollVoteRequest.selected) {
                                // Prevent double voting on same option
                                if (option.voters.none { it.userId == requestingUserId }) {
                                    option.copy(
                                        voters = option.voters + PollVoter(
                                            userId = requestingUserId,
                                            votedAt = timeStamp
                                        )
                                    )
                                } else {
                                    option // Already voted, return unchanged
                                }
                            } else {

                                //User unselected this option, remove him as voter if he exists
                                option.copy(
                                    voters = option.voters.filter { it.userId != requestingUserId }
                                )
                            }
                        } else {
                            option
                        }
                    }
                )
            }

            val query = Query(
                Criteria.where("_id").`is`(message.id)
                    .and("lastChanged.epochSeconds").`is`(message.lastChanged.epochSeconds)
                    .and("lastChanged.nanosecondsOfSecond").`is`(message.lastChanged.nanosecondsOfSecond)
            )

            val update = Update()
                .set("lastChanged", timeStamp)
                .set("poll", poll)

            val savedMessage = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                Message::class.java
            ) ?: throw OptimisticLockingFailureException("Message was modified by another request")


            notificationService.notifyMessageUpdate(
                message = savedMessage,
                newMessage = false,
                deleted = false,
                changingUserId = requestingUserId
            )

            //Poll update is finished(test with beta users) save and return
            savedMessage
        }
    }



    fun editMessage(messageId: ObjectId, editingUserId: ObjectId, newContent: String) : MessageResponse {


        require(ValidationUtils.validateStringMessage(newContent)) { "Invalid new content"}

        val message = canUserAccessMessage(messageId, editingUserId)

        require(message.msgType == MessageType.TEXT || message.msgType == MessageType.IMAGE) { "You can not edit a ${message.msgType} message" }

        //User can access message, change content
        val now = Clock.System.now()

        val newmessage = messageRepository.save(message.copy(
            lastChanged = now,
            content = newContent,
            edited = true
        ))

        notificationService.notifyMessageUpdate(
            message = newmessage,
            newMessage = false,
            deleted = false,
            changingUserId = editingUserId
        )

        return newmessage.toMessageResponse(editingUserId)
    }


    fun getImageMessage(messageId: ObjectId, requestingUserId: ObjectId) : ByteArray {
        val message = canUserAccessMessage(messageId, requestingUserId)

        require(message.msgType == MessageType.IMAGE) { "You can not access not image messages on this endpoint" }

        return imageManager.loadMessageImageFromFile(imageManager.getImageMessageFileName(
            messageId = messageId,
            group = message.groupMessage
        ))
    }

    fun getAudioMessage(messageId: ObjectId, requestingUserId: ObjectId) : ByteArray {
        val message = canUserAccessMessage(messageId, requestingUserId)

        require(message.msgType == MessageType.AUDIO) { "You can not access not audio messages on this endpoint" }

        return audioManager.loadMessageAudioFromFile(audioManager.getAudioMessageFileName(
            messageId = messageId,
            group = message.groupMessage
        ))
    }


    fun deleteMessage(messageId: ObjectId, deletingUserId: ObjectId) {
        val message = canUserAccessMessage(messageId, deletingUserId)

        require(message.senderId == deletingUserId) { "Only the sender can delete a message" }

        loggingService.log(
            userId = deletingUserId,
            logType = LogType.MESSAGE_DELETED
        )

        val updatedMessage = messageRepository.save(message.copy(deleted = true))

        notificationService.notifyMessageUpdate(
            message = updatedMessage,
            newMessage = false,
            deleted = true,
            changingUserId = deletingUserId
        )
    }



    fun setMessagesRead(readingUser: ObjectId, chat: ObjectId, group: Boolean, timeStamp: Long) {
        val serverInstant = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val clientInstant = Instant.fromEpochMilliseconds(timeStamp)

        // allowed difference: ±1 minute
        val maxDiff = Duration.parse("1m")
        val usedInstant =
            if ((serverInstant - clientInstant).absoluteValue <= maxDiff)
                clientInstant
            else
                serverInstant

        val query = if (group) {
            // For group messages: find all messages sent to this group
            // that the user hasn't read yet
            if (!groupLookupService.isUserInGroup(readingUser, chat)) {
                println("User $readingUser is not a member of group $chat")
                return
            }
            
            Query().addCriteria(
                Criteria.where("receiverId").`is`(chat)
                    .and("groupMessage").`is`(true)
                    .and("readers.userId").ne(readingUser)
            )
        } else {
            // For direct messages: find messages between the two users
            val conversationCriteria = Criteria().orOperator(
                Criteria().andOperator(
                    Criteria.where("senderId").`is`(readingUser),
                    Criteria.where("receiverId").`is`(chat)
                ),
                Criteria().andOperator(
                    Criteria.where("senderId").`is`(chat),
                    Criteria.where("receiverId").`is`(readingUser)
                )
            )
            
            Query().addCriteria(
                conversationCriteria
                    .and("groupMessage").`is`(false)
                    .and("readers.userId").ne(readingUser)
            )
        }

        val messagesToUpdate = mongoTemplate.find<Message>(query, "messages")

        // Build reader object to push into the readers array
        val readerDoc = mapOf(
            "userId" to readingUser,
            "readAt" to usedInstant
        )

        val update = Update()
            .addToSet("readers", readerDoc)
            .max("lastChanged", usedInstant)

        val result = mongoTemplate.updateMulti(query, update, "messages")


        if (result.modifiedCount > 0) {
            // Fetch the updated messages with the new reader info
            val updatedQuery = Query().addCriteria(
                Criteria.where("_id").`in`(messagesToUpdate.map { it.id })
            )
            val updatedMessages = mongoTemplate.find<Message>(updatedQuery, "messages")

            updatedMessages.forEach { message ->
                try {
                    notificationService.notifyMessageUpdate(
                        message = message,
                        newMessage = false,
                        deleted = false,
                        changingUserId = readingUser,
                    )
                } catch (e: Exception) {
                    println("Failed to notify message update for ${message.id}: ${e.message}")
                }
            }
        }



        //println("Marked read — modifiedCount = ${result.modifiedCount}")
    }



    data class MessageSyncResponse(
        val updatedMessages: List<MessageResponse>,
        val deletedMessages: List<String>,
        val moreMessages: Boolean
    )

    fun messageSync(clientMessages: List<UserService.IdTimeStamp>, requestingUser: ObjectId, page: Int, pageSize: Int) : MessageSyncResponse {

        val clientMessagesMap = clientMessages.associate {
            it.id to it.timeStamp
        }

        val allMessages = getAllUserMessages(requestingUser)

        val messagesToAdd = allMessages
            .filter { it.id.toHexString() !in clientMessagesMap.keys }

        val messagesToUpdate = allMessages
            .filter { message ->
                clientMessagesMap[message.id.toHexString()]?.toLong()?.let { clientTimestamp ->
                    message.lastChanged.toEpochMilliseconds() > clientTimestamp
                } ?: false
            }

        val currentMessageIdStrings = allMessages.map { it.id.toHexString() }.toSet()
        val messagesToRemove = clientMessagesMap.keys.filter { it !in currentMessageIdStrings }

        // Combine and sort by sendDate descending (most recent first)
        val allMessagesToUpdate = (messagesToAdd + messagesToUpdate)
            .sortedByDescending { it.sendDate.toEpochMilliseconds() }

        // Apply pagination
        val startIndex = page * pageSize
        val endIndex = startIndex + pageSize

        val paginatedMessages = allMessagesToUpdate
            .drop(startIndex)
            .take(pageSize)
            .map { it.toMessageResponse(requestingUser) }

        val moreMessages = endIndex < allMessagesToUpdate.size

        return MessageSyncResponse(
            updatedMessages = paginatedMessages,
            deletedMessages = if (page == 0) messagesToRemove else emptyList(), // Only send deletes on first page
            moreMessages = moreMessages
        )
    }

    private fun getAllUserMessages(userId: ObjectId): List<Message> {
        val userGroups = groupLookupService.getUserGroupIds(userId)

        val query = Query()
        // Build criteria: user is sender OR receiver OR (groupMessage AND receiver is in user's groups)
        val criteria = Criteria().orOperator(
            Criteria.where("senderId").`is`(userId),
            Criteria.where("receiverId").`is`(userId),
            Criteria().andOperator(
                Criteria.where("groupMessage").`is`(true),
                Criteria.where("receiverId").`in`(userGroups)
            )
        )

        query.addCriteria(criteria)

        query.addCriteria(Criteria.where("deleted").`is`(false))
        query.with(Sort.by(Sort.Direction.DESC, "sendDate"))


        return mongoTemplate.find(query, Message::class.java)
    }


    private fun canUserAccessMessage(
        messageId: ObjectId,
        userId: ObjectId,
    ) : Message {
        val message = messageRepository.findById(messageId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND)
        }

        if (message.groupMessage) {
            canUserAccessMessage(userId, message.receiverId, true)
        } else {
            // For direct messages, user must be either sender or receiver
            require(message.senderId == userId || message.receiverId == userId) {
                "You do not have access to this message"
            }
        }

        return message
    }


    /**
     * Check if a user can access a message. throws if not
     */
    private fun canUserAccessMessage(sender: ObjectId, receiver: ObjectId, groupMessage: Boolean) {
        if (groupMessage) {
            require(groupLookupService.isUserInGroup(sender, receiver)) {
                "You are not a member of this group"
            }
        } else {
            //Single message
            require(sender != receiver) {
                "You can not send messages to yourself"
            }
            require(friendsService.areFriends(sender, receiver)) {
                "You can not send messages to users who are not your friends"
            }
        }
    }

}