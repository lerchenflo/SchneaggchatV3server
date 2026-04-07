package com.lerchenflo.schneaggchatv3server.message

import com.lerchenflo.schneaggchatv3server.message.messagemodel.Message
import com.lerchenflo.schneaggchatv3server.repository.MessageRepository
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import kotlin.jvm.optionals.getOrNull

@Component
class MessageLookupService(
    private val messageRepository: MessageRepository
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

}