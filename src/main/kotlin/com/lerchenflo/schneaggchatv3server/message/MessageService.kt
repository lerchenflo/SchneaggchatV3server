@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.message

import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.message.MessageService.MessageContent.Image
import com.lerchenflo.schneaggchatv3server.message.MessageService.MessageContent.Text
import com.lerchenflo.schneaggchatv3server.message.messagemodel.*
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageType.*
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsService
import com.lerchenflo.schneaggchatv3server.util.*
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.mongodb.core.BulkOperations
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
    private val messageLookupService: MessageLookupService,

    private val friendsService: FriendsService,
    private val friendsLookupService: FriendsLookupService,

    private val groupLookupService: GroupLookupService,
    private val userLookupService: UserLookupService,
    private val imageManager: ImageManager,
    private val audioManager: AudioManager,
    private val notificationService: NotificationService,
    private val loggingService: LoggingService,
    private val versionCounterService: VersionCounterService,
) {

    companion object {
        //Creation caps at 20 options; custom answers added later via votePoll may grow the poll up to this
        private const val POLL_MAX_TOTAL_OPTIONS = 50
    }

    sealed class MessageContent {
        data class Text(val message: String) : MessageContent()
        data class Image(val image: MultipartFile, val text: String) : MessageContent()
        data class Audio(val audio: MultipartFile) : MessageContent()

        data class Poll(val poll: PollMessage) : MessageContent()
    }

    fun sendMessage(sender: ObjectId, receiver: ObjectId, groupMessage: Boolean, messageType: MessageType, content: MessageContent, answerId: ObjectId?, clientMessageId: String) : Message {

        canUserAccessMessage(
            sender = sender,
            receiver = receiver,
            groupMessage = groupMessage,
        )

        // Idempotency: a retried send (offline queue, lost response, replayed auth-refresh
        // request) carries the same clientMessageId as the original attempt. Resolve it to the
        // already-created message before doing any validation or writing any media to disk -
        // an orphaned image/audio file keyed on a discarded ObjectId is otherwise unrecoverable.
        messageLookupService.findByClientMessageId(sender, clientMessageId)?.let { return it }



        when (messageType) {
            TEXT -> {
                require(content is Text) { "Message type must be Text" }

                require(ValidationUtils.validateStringMessage(content.message)) { "Invalid text message" }
            }
            IMAGE -> {

                require(content is Image) { "Image message type must be Image" }

                if (content.text.isNotEmpty()) {
                    require(ValidationUtils.validateStringMessage(content.text)) { "Invalid text message" }
                }

                require(ValidationUtils.validatePicture(content.image)) { "Invalid image" }
            }
            POLL -> {
                require(content is MessageContent.Poll) { "Pollmessage with empty poll" }

                require(ValidationUtils.validatePollTitle(content.poll.title)) { "Invalid poll title" }
                require(ValidationUtils.validatePollDescription(content.poll.description)) { "Invalid poll description" }
                require(content.poll.voteOptions.size <= 20) { "Poll can have at most 20 vote options" }
                //A poll needs predefined options unless it's custom-answers-only
                if (!content.poll.customAnswersEnabled) {
                    require(content.poll.voteOptions.isNotEmpty()) { "Poll must have at least 1 vote option unless custom answers are enabled" }
                }
                content.poll.maxAnswers?.let {
                    require(it in 1..20) { "Invalid maxAnswers" }
                    //Without custom answers, users can't select more distinct answers than exist
                    if (!content.poll.customAnswersEnabled) {
                        require(it <= content.poll.voteOptions.size) { "maxAnswers can't exceed the number of vote options" }
                    }
                }
                content.poll.maxAllowedCustomAnswers?.let {
                    require(content.poll.customAnswersEnabled) { "maxAllowedCustomAnswers set but custom answers disabled" }
                    require(it in 1..20) { "Invalid maxAllowedCustomAnswers" }
                }

                if (content.poll.closeDate != null) {
                    require(content.poll.closeDate > Clock.System.now()) { "Poll closedate is in the past" }
                }

                content.poll.voteOptions.forEach { voteOption ->
                    require(ValidationUtils.validatePollVoteText(voteOption.text)) {"Pollvote option text in wrong format"}
                }

                //A list-mode poll (no checkboxes) does not vote, so answer limits are meaningless
                if (!content.poll.showCheckboxes) {
                    require(content.poll.maxAnswers == null) { "maxAnswers is not allowed on a list poll" }
                    require(content.poll.voteOptions.none { it.maxVoters != null }) { "maxVoters is not allowed on a list poll" }
                }
            }
            AUDIO -> {

                require(content is MessageContent.Audio) { "Audio message type must be Audio" }

                require(ValidationUtils.validateAudio(content.audio)) { "Invalid audio" }
            }

            SYSTEM -> {
                //User can not send system messages
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A user can not send a SYSTEM message!")
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

        // Two retries of the same send can both pass the pre-check above concurrently; the
        // unique index is the actual race guard, this just resolves the loser back to the
        // winner's document instead of surfacing a 500.
        val (message, deduped) = try {
            versionCounterService.withVersion(SyncCollection.MESSAGES) { version ->
                messageLookupService.saveMessage(Message(
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
                    version = version,
                    readers = listOf(Reader(
                        userId = sender,
                        readAt = sendDate
                    )),
                    clientMessageId = clientMessageId,
                ))
            } to false
        } catch (e: DuplicateKeyException) {
            val existing = messageLookupService.findByClientMessageId(sender, clientMessageId) ?: throw e
            existing to true
        }

        // A dedup hit is not a new message: no version was allocated for it, so re-notifying
        // would fire a socket/push frame with no corresponding sync-cursor advance.
        if (!deduped) {
            notificationService.notifyMessageUpdate(
                message = message,
                newMessage = true,
                deleted = false,
                changingUserId = sender
            )
        }

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

            //A list-mode poll (no checkboxes) only accepts new custom options, never a vote on an existing one
            if (!poll.showCheckboxes) {
                require(pollVoteRequest.id == null) { "This poll does not accept votes" }
            }

            //Block answers after expiry
            if (poll.closeDate != null) {
                require(Clock.System.now() < poll.closeDate) { "Poll is closed" }
            }

            //Block new selections on a full entry (unselecting your own claim is always allowed)
            val targetOption = poll.voteOptions.find { it.id == pollVoteRequest.id }
            if (pollVoteRequest.selected && targetOption?.maxVoters != null) {
                val claimedByOthers = targetOption.voters.count { it.userId != requestingUserId }
                require(claimedByOthers < targetOption.maxVoters) { "This entry is full" }
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

                //Hard cap on total options regardless of per-user limits
                require(poll.voteOptions.size < POLL_MAX_TOTAL_OPTIONS) { "This poll has reached the maximum number of options" }

                //If atleast one of the options has a limit set, the user can set a limit for voters on his custom entry
                val newPollOptionMaxAnswers = if (poll.voteOptions.any { it.maxVoters != null }) {
                    pollVoteRequest.maxAllowedAnswers
                } else null

                //User created a new option
                poll = poll.copy(
                    voteOptions = poll.voteOptions + PollVoteOption(
                        id = ObjectId.get().toHexString(),
                        text = pollVoteRequest.text!!,
                        custom = true,
                        creatorId = requestingUserId,
                        maxVoters = newPollOptionMaxAnswers,

                        //User automatically votes for his created item, unless this is a list-mode poll (no voting)
                        voters = if (poll.showCheckboxes) listOf(PollVoter(
                            userId = requestingUserId,
                            votedAt = timeStamp
                        )) else emptyList()
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

            val savedMessage = versionCounterService.withVersion(SyncCollection.MESSAGES) { version ->
                val update = Update()
                    .set("lastChanged", timeStamp)
                    .set("poll", poll)
                    .set("version", version)

                mongoTemplate.findAndModify(
                    query,
                    update,
                    FindAndModifyOptions.options().returnNew(true),
                    Message::class.java
                )
            } ?: throw OptimisticLockingFailureException("Message was modified by another request")


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


    fun deletePollOption(requestingUserId: ObjectId, request: PollOptionDeleteRequest): Message {
        return withOptimisticRetry {
            val message = canUserAccessMessage(
                messageId = ObjectId(request.messageId),
                userId = requestingUserId
            )

            require(message.msgType == MessageType.POLL && message.poll != null) { "This is not a poll message" }

            val poll = message.poll

            require(poll.allowDeleteOptions) { "Deleting options is not allowed for this poll" }

            if (poll.closeDate != null) {
                require(Clock.System.now() < poll.closeDate) { "Poll is closed" }
            }

            val option = poll.voteOptions.find { it.id == request.optionId }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Vote option not found")

            requireOrLog(
                poll.canUserDeleteOption(requestingUserId, option),
                { "Poll option deletion denied - user: ${userLookupService.getUsername(requestingUserId)}, messageId: ${request.messageId}, optionId: ${request.optionId}" }
            ) { "You can only delete options you created" }

            val timeStamp = Clock.System.now()
            val newPoll = poll.copy(voteOptions = poll.voteOptions.filterNot { it.id == request.optionId })

            val query = Query(
                Criteria.where("_id").`is`(message.id)
                    .and("lastChanged.epochSeconds").`is`(message.lastChanged.epochSeconds)
                    .and("lastChanged.nanosecondsOfSecond").`is`(message.lastChanged.nanosecondsOfSecond)
            )

            val savedMessage = versionCounterService.withVersion(SyncCollection.MESSAGES) { version ->
                val update = Update()
                    .set("lastChanged", timeStamp)
                    .set("poll", newPoll)
                    .set("version", version)

                mongoTemplate.findAndModify(
                    query,
                    update,
                    FindAndModifyOptions.options().returnNew(true),
                    Message::class.java
                )
            } ?: throw OptimisticLockingFailureException("Message was modified by another request")

            loggingService.log(
                userId = requestingUserId,
                logType = LogType.POLL_OPTION_DELETED
            )

            notificationService.notifyMessageUpdate(
                message = savedMessage,
                newMessage = false,
                deleted = false,
                changingUserId = requestingUserId
            )

            savedMessage
        }
    }


    fun editMessage(messageId: ObjectId, editingUserId: ObjectId, newContent: String) : MessageResponse {


        require(ValidationUtils.validateStringMessage(newContent)) { "Invalid new content"}

        val message = canUserAccessMessage(messageId, editingUserId)

        require(message.msgType == MessageType.TEXT || message.msgType == IMAGE) { "You can not edit a ${message.msgType} message" }

        //User can access message, change content
        val now = Clock.System.now()

        val newmessage = versionCounterService.withVersion(SyncCollection.MESSAGES) { version ->
            messageLookupService.saveMessage(message.copy(
                lastChanged = now,
                content = newContent,
                edited = true,
                version = version,
            ))
        }

        notificationService.notifyMessageUpdate(
            message = newmessage,
            newMessage = false,
            deleted = false,
            changingUserId = editingUserId
        )

        return newmessage.toMessageResponse(editingUserId)
    }


    fun reactToMessage(messageId: ObjectId, reactingUserId: ObjectId, content: String): MessageResponse {
        return withOptimisticRetry {
            require(ValidationUtils.validateReactionContent(content)) { "Invalid reaction content" }

            val message = canUserAccessMessage(messageId, reactingUserId)
            require(!message.deleted) { "Cannot react to deleted message" }
            require(message.msgType != MessageType.SYSTEM) { "Cannot react to a system message" }

            val existing = message.reactions.firstOrNull {
                it.userId == reactingUserId && it.content == content
            }

            val isAdd = existing == null

            val now = Clock.System.now()

            val newReactions = if (existing != null) {
                message.reactions - existing
            } else {
                message.reactions + Reaction(userId = reactingUserId, content = content.trim(), reactedAt = now)
            }

            val query = Query(
                Criteria.where("_id").`is`(message.id)
                    .and("lastChanged.epochSeconds").`is`(message.lastChanged.epochSeconds)
                    .and("lastChanged.nanosecondsOfSecond").`is`(message.lastChanged.nanosecondsOfSecond)
            )

            val savedMessage = versionCounterService.withVersion(SyncCollection.MESSAGES) { version ->
                val update = Update()
                    .set("lastChanged", now)
                    .set("reactions", newReactions)
                    .set("version", version)

                mongoTemplate.findAndModify(
                    query,
                    update,
                    FindAndModifyOptions.options().returnNew(true),
                    Message::class.java
                )
            } ?: throw OptimisticLockingFailureException("Message was modified by another request")

            notificationService.notifyMessageUpdate(
                message = savedMessage,
                newMessage = false,
                deleted = false,
                changingUserId = reactingUserId
            )

            if (isAdd) {
                notificationService.notifyReactionAdded(
                    message = savedMessage,
                    reactorId = reactingUserId,
                    reactionContent = content,
                )
            }

            savedMessage.toMessageResponse(reactingUserId)
        }
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

        require(message.msgType != MessageType.SYSTEM) { "Cannot delete a system message" }
        requireOrLog(
            message.senderId == deletingUserId,
            { "Message deletion denied - user: ${userLookupService.getUsername(deletingUserId)}, messageId: ${messageId.toHexString()}, sender: ${userLookupService.getUsername(message.senderId)}" }
        ) { "Only the sender can delete a message" }

        when (message.msgType) {
            MessageType.IMAGE -> imageManager.deleteMessageImage(messageId, message.groupMessage)
            MessageType.AUDIO -> audioManager.deleteMessageAudio(messageId, message.groupMessage)
            else -> {}
        }

        loggingService.log(
            userId = deletingUserId,
            logType = LogType.MESSAGE_DELETED
        )

        val updatedMessage = versionCounterService.withVersion(SyncCollection.MESSAGES) { version ->
            messageLookupService.saveMessage(message.copy(
                deleted = true,
                lastChanged = Clock.System.now(),
                version = version,
            ))
        }

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
                AppLogger.warn("User $readingUser is not a member of group $chat")
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

        if (messagesToUpdate.isEmpty()) {
            return
        }

        // Build reader object to push into the readers array
        val readerDoc = mapOf(
            "userId" to readingUser,
            "readAt" to usedInstant
        )

        // A plain `updateMulti` can't hand out a distinct version per document, so this needs a
        // bulk write - one `version` per message, reserved as a contiguous block up front. Read
        // receipts always advance `lastChanged`/`version` now ($set, not the old $max): a stale
        // client-supplied timestamp must still bump the sync cursor.
        val updatedMessages = versionCounterService.withVersions(SyncCollection.MESSAGES, messagesToUpdate.size) { versions ->
            val bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, Message::class.java)

            messagesToUpdate.forEachIndexed { index, message ->
                val update = Update()
                    .addToSet("readers", readerDoc)
                    .set("lastChanged", usedInstant)
                    .set("version", versions.first + index)

                bulkOps.updateOne(Query(Criteria.where("_id").`is`(message.id)), update)
            }

            bulkOps.execute()

            val updatedQuery = Query().addCriteria(
                Criteria.where("_id").`in`(messagesToUpdate.map { it.id })
            )
            mongoTemplate.find<Message>(updatedQuery, "messages")
        }

        updatedMessages.forEach { message ->
            try {
                notificationService.notifyMessageUpdate(
                    message = message,
                    newMessage = false,
                    deleted = false,
                    changingUserId = readingUser,
                )
            } catch (e: Exception) {
                AppLogger.error("Failed to notify message update for ${message.id}: ${e.message}")
            }
        }

        //println("Marked read — updated ${updatedMessages.size} messages")
    }



    data class MessageSyncResponse(
        val updatedMessages: List<MessageResponse>,
        val deletedMessages: List<String>,
        val newVersion: Long,
        val moreMessages: Boolean,
    )

    /**
     * Version-based incremental sync. The client sends the highest `version` it has already seen
     * (`since`); this returns every message visible to [requestingUser] with a higher version, up
     * to [pageSize] at a time, oldest-first, plus the ids of anything soft-deleted in that range.
     * The client loops, passing back `newVersion` as the next `since`, until `moreMessages` is false.
     *
     * Bounded by [VersionCounterService.safeWatermark] rather than the live counter so an in-flight
     * write (version already allocated, document not yet persisted) can never be skipped.
     */
    fun messageSync(since: Long, requestingUser: ObjectId, pageSize: Int): MessageSyncResponse {
        val watermark = versionCounterService.safeWatermark(SyncCollection.MESSAGES)

        val fetched = messageLookupService.getMessagesSince(
            userId = requestingUser,
            since = since,
            watermark = watermark,
            limit = pageSize,
        )

        val page = fetched.take(pageSize)
        val moreMessages = fetched.size > pageSize

        val (deleted, updated) = page.partition { it.deleted }

        return MessageSyncResponse(
            updatedMessages = updated.map { it.toMessageResponse(requestingUser) },
            deletedMessages = deleted.map { it.id.toHexString() },
            newVersion = page.maxOfOrNull { it.version } ?: since,
            moreMessages = moreMessages,
        )
    }




    private fun canUserAccessMessage(
        messageId: ObjectId,
        userId: ObjectId,
    ) : Message {
        val message = messageLookupService.findById(messageId) ?: run {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }

        if (message.groupMessage) {
            canUserAccessMessage(userId, message.receiverId, true)
        } else {
            // For direct messages, user must be either sender or receiver
            requireOrLog(
                message.senderId == userId || message.receiverId == userId,
                { "Message access denied - user: ${userLookupService.getUsername(userId)}, messageId: ${messageId.toHexString()}, sender: ${userLookupService.getUsername(message.senderId)}, receiver: ${userLookupService.getUsername(message.receiverId)}" }
            ) { "You do not have access to this message" }
        }

        return message
    }


    /**
     * Check if a user can access a message. throws if not
     */
    private fun canUserAccessMessage(sender: ObjectId, receiver: ObjectId, groupMessage: Boolean) {
        if (groupMessage) {
            requireOrLog(
                groupLookupService.isUserInGroup(sender, receiver),
                { "Unauthorized message access - user: ${userLookupService.getUsername(sender)}, group: ${groupLookupService.getGroupName(receiver)}: Not in group" }
            ) { "You are not a member of this group" }
        } else {
            //Single message
            require(sender != receiver) {
                "You can not send messages to yourself"
            }
            requireOrLog(
                friendsLookupService.areFriends(sender, receiver),
                { "Unauthorized message access - user: ${userLookupService.getUsername(sender)} is not friends with: ${userLookupService.getUsername(receiver)}" }
            ) { "You can not send messages to users who are not your friends" }
        }
    }

}