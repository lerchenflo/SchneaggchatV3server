package com.lerchenflo.schneaggchatv3server.message

import com.lerchenflo.schneaggchatv3server.message.messagemodel.Message
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageType
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.AudioManager
import com.lerchenflo.schneaggchatv3server.util.ImageManager
import com.lerchenflo.schneaggchatv3server.util.SyncCollection
import com.lerchenflo.schneaggchatv3server.util.VersionCounterService
import org.bson.Document
import org.bson.types.ObjectId
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.getCollectionName
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Image and voice files are kept [MEDIA_RETENTION] after the message's sendDate. After that only
 * the file is deleted: the message stays (caption, reactions, replies) and gets
 * `mediaExpired = true` with a new version, so `/messages/sync` hands the change to every client.
 * No socket frame or push. Text, poll and system messages are never touched.
 */
@Component
class MediaRetentionService(
    private val mongoTemplate: MongoTemplate,
    private val versionCounterService: VersionCounterService,
    private val imageManager: ImageManager,
    private val audioManager: AudioManager,
) {
    companion object {
        val MEDIA_RETENTION = 60.days
        private const val BATCH_SIZE = 500
    }

    /** Daily at 03:00 Vienna time. The API test sets `MEDIA_RETENTION_CRON` to run it every few seconds. */
    @Scheduled(cron = "\${media.retention.cron:0 0 3 * * *}", zone = "Europe/Vienna")
    fun expireOldMedia() {
        val cutoffSeconds = (Clock.System.now() - MEDIA_RETENTION).epochSeconds
        var lastId: ObjectId? = null
        var expired = 0

        // Paged by _id, so a message whose update fails is skipped instead of being found again forever
        while (true) {
            val criteria = Criteria.where("msgType").`in`(MessageType.IMAGE.name, MessageType.AUDIO.name)
                .and("deleted").`is`(false)
                .and("mediaExpired").ne(true) // messages stored before the field have none
                .and("sendDate.epochSeconds").lt(cutoffSeconds)
            lastId?.let { criteria.and("_id").gt(it) }

            // Raw documents with only the fields needed: a projected Message can't be constructed
            val query = Query(criteria).with(Sort.by(Sort.Direction.ASC, "_id")).limit(BATCH_SIZE)
            query.fields().include("_id", "msgType", "groupMessage")
            val batch = mongoTemplate.find(query, Document::class.java, mongoTemplate.getCollectionName<Message>())

            batch.forEach { document ->
                val messageId = document.getObjectId("_id")
                if (expire(messageId, document.getString("msgType"), document.getBoolean("groupMessage") == true)) expired++
            }

            if (batch.size < BATCH_SIZE) break
            lastId = batch.last().getObjectId("_id")
        }

        if (expired > 0) {
            AppLogger.info("Media retention: removed the files of $expired messages older than $MEDIA_RETENTION", tag = "media_retention")
        }
    }

    /**
     * File first, flag second: if the flag write fails, the next run finds the message again and
     * the file delete is a no-op. The other way round, a failed file delete would leave the file
     * on disk forever.
     * @return whether the message was marked (false when it was deleted or marked in the meantime)
     */
    private fun expire(messageId: ObjectId, msgType: String, group: Boolean): Boolean {
        when (msgType) {
            MessageType.IMAGE.name -> imageManager.deleteMessageImage(messageId, group)
            MessageType.AUDIO.name -> audioManager.deleteMessageAudio(messageId, group)
        }

        val query = Query(
            Criteria.where("_id").`is`(messageId)
                .and("deleted").`is`(false)
                .and("mediaExpired").ne(true)
        )

        return versionCounterService.withVersion(SyncCollection.MESSAGES) { version ->
            // lastChanged moves too, so an edit or reaction racing this write retries on the new state
            val update = Update()
                .set("mediaExpired", true)
                .set("lastChanged", Clock.System.now())
                .set("version", version)
            mongoTemplate.updateFirst(query, update, Message::class.java).modifiedCount > 0
        }
    }
}
