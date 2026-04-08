package com.lerchenflo.schneaggchatv3server.message

import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.message.messagemodel.Message
import com.lerchenflo.schneaggchatv3server.repository.MessageRepository
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
    private val groupLookupService: GroupLookupService
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


    fun deleteAllUserMessages(userId: ObjectId) {
        val messages = getAllUserMessages(
            userId = userId,
            includeDeleted = true
        )

        messageRepository.deleteAll(messages)
    }

}