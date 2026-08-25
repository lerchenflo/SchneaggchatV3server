package com.lerchenflo.schneaggchatv3server.message

import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.message.messagemodel.Message
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageType
import com.lerchenflo.schneaggchatv3server.repository.MessageRepository
import com.lerchenflo.schneaggchatv3server.util.AudioManager
import com.lerchenflo.schneaggchatv3server.util.ImageManager
import org.bson.types.ObjectId
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component
import kotlin.jvm.optionals.getOrNull

@Component
class MessageLookupService(
    private val messageRepository: MessageRepository,

    private val mongoTemplate: MongoTemplate,
    private val groupLookupService: GroupLookupService,
    private val imageManager: ImageManager,
    private val audioManager: AudioManager,
) {
    fun saveMessage(message: Message) : Message {
        return messageRepository.save(message)
    }

    fun findById(messageId: ObjectId) : Message? {
        return messageRepository.findById(messageId).getOrNull()
    }

    fun count() : Long {
        return messageRepository.count()
    }

    fun getAllUserMessages(userId: ObjectId, includeDeleted: Boolean = false): List<Message> {
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

        if (!includeDeleted) {
            query.addCriteria(Criteria.where("deleted").`is`(false))
        }

        query.with(Sort.by(Sort.Direction.DESC, "sendDate"))


        return mongoTemplate.find(query, Message::class.java)
    }


    /**
     * Messages visible to [userId] with `since < version <= watermark`, oldest-first, capped at
     * [limit] + 1 (the caller uses the extra row purely to detect "more pages exist" without a
     * second count query). Backs `/messages/sync` - see `MessageService.messageSync`.
     *
     * Soft-deleted messages are deliberately included: a deleted message's `version` bump is the
     * tombstone the client relies on to remove it locally.
     *
     * Groups joined after [since] was already reached (`GroupMember.joinedAtVersion > since`) have
     * their `version` floor dropped entirely, so a client catches up on the group's full history
     * exactly once - the run it first learns about the group via `/groups/sync`.
     */
    fun getMessagesSince(userId: ObjectId, since: Long, watermark: Long, limit: Int): List<Message> {
        val userGroups = groupLookupService.getUserGroupIds(userId)
        val fullHistoryGroups = groupLookupService.getGroupIdsJoinedAfterVersion(userId, since)

        val visibility = Criteria().orOperator(
            Criteria.where("senderId").`is`(userId),
            Criteria.where("receiverId").`is`(userId),
            Criteria().andOperator(
                Criteria.where("groupMessage").`is`(true),
                Criteria.where("receiverId").`in`(userGroups)
            )
        )

        val versionFloors = mutableListOf<Criteria>(
            Criteria.where("version").gt(since).lte(watermark)
        )
        if (fullHistoryGroups.isNotEmpty()) {
            versionFloors += Criteria().andOperator(
                Criteria.where("groupMessage").`is`(true),
                Criteria.where("receiverId").`in`(fullHistoryGroups),
                Criteria.where("version").lte(watermark)
            )
        }

        val query = Query(
            Criteria().andOperator(
                visibility,
                Criteria().orOperator(*versionFloors.toTypedArray())
            )
        )
            .with(Sort.by(Sort.Direction.ASC, "version"))
            .limit(limit + 1)

        return mongoTemplate.find(query, Message::class.java)
    }

    fun deleteAllUserMessages(userId: ObjectId) {
        val messages = getAllUserMessages(
            userId = userId,
            includeDeleted = true
        )

        messages.forEach { message ->
            when (message.msgType) {
                MessageType.IMAGE -> imageManager.deleteMessageImage(message.id, message.groupMessage)
                MessageType.AUDIO -> audioManager.deleteMessageAudio(message.id, message.groupMessage)
                else -> {}
            }
        }

        messageRepository.deleteAll(messages)
    }

}