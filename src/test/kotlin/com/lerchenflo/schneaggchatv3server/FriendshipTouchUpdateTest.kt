@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.core.MongoConfig
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.Friendship
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipStatus
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.convert.MappingMongoConverter
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver
import org.springframework.data.mongodb.core.convert.UpdateMapper
import org.springframework.data.mongodb.core.mapping.MongoMappingContext
import org.springframework.data.mongodb.core.query.Update
import java.util.Date
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * `FriendsLookupService.touchFriendship` sets `updatedAt` through a MongoTemplate [Update]. It must
 * write the same `{epochSeconds, nanosecondsOfSecond}` sub-document a full save writes for a
 * `kotlin.time.Instant` - never a BSON Date - or reading the row back would fail.
 */
@DisplayName("Friendship touch update Tests")
class FriendshipTouchUpdateTest {

    private val mappingContext = MongoMappingContext()

    // Same conversions as the app, without a database
    private val converter: MappingMongoConverter = run {
        val conversions = MongoConfig().customConversions()
        mappingContext.apply {
            setSimpleTypeHolder(conversions.simpleTypeHolder)
            afterPropertiesSet()
        }
        MappingMongoConverter(NoOpDbRefResolver.INSTANCE, mappingContext).apply {
            setCustomConversions(conversions)
            afterPropertiesSet()
        }
    }

    private val at = Instant.fromEpochSeconds(1_763_200_000, 123_456_789)

    private fun mappedSet(): Document {
        val mapped = UpdateMapper(converter).getMappedObject(
            Update().set("updatedAt", at).updateObject,
            mappingContext.getRequiredPersistentEntity(Friendship::class.java),
        )
        return mapped["\$set"] as Document
    }

    @Test
    fun `the update writes the same sub-document as a full save`() {
        val saved = Document()
        converter.write(
            Friendship(userId1 = ObjectId(), userId2 = ObjectId(), requesterId = ObjectId(), status = FriendshipStatus.PENDING, updatedAt = at),
            saved,
        )

        val updated = mappedSet()["updatedAt"]

        assertFalse(updated is Date, "updatedAt was written as a BSON Date: $updated")
        assertTrue(updated is Document, "updatedAt is not a sub-document: $updated (${updated?.javaClass})")
        assertEquals(saved["updatedAt"], updated)
        assertEquals(setOf("epochSeconds", "nanosecondsOfSecond"), (updated as Document).keys)
        assertEquals(1_763_200_000L, (updated["epochSeconds"] as Number).toLong())
        assertEquals(123_456_789, (updated["nanosecondsOfSecond"] as Number).toInt())
    }

    @Test
    fun `a row with the updated field reads back as kotlin time`() {
        val saved = Document()
        converter.write(
            Friendship(userId1 = ObjectId(), userId2 = ObjectId(), requesterId = ObjectId(), status = FriendshipStatus.BLOCKED),
            saved,
        )
        saved["updatedAt"] = mappedSet()["updatedAt"]

        val read = converter.read(Friendship::class.java, saved)

        assertEquals(at, read.updatedAt)
        assertEquals(FriendshipStatus.BLOCKED, read.status)
    }
}
