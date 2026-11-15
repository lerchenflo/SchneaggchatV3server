@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.core.MongoConfig
import com.lerchenflo.schneaggchatv3server.user.UserController
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipStatus
import com.lerchenflo.schneaggchatv3server.user.usermodel.PersonalUserSettings
import com.lerchenflo.schneaggchatv3server.user.usermodel.ProfilePicVisibility
import com.lerchenflo.schneaggchatv3server.user.usermodel.ProfilePicVisibility.FRIENDS
import com.lerchenflo.schneaggchatv3server.user.usermodel.ProfilePicVisibility.FRIENDS_AND_GROUPS
import com.lerchenflo.schneaggchatv3server.user.usermodel.ProfilePicVisibility.PUBLIC
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import com.lerchenflo.schneaggchatv3server.user.usermodel.allows
import com.lerchenflo.schneaggchatv3server.user.usermodel.toFriendUserResponse
import com.lerchenflo.schneaggchatv3server.user.usermodel.toSimpleUserResponse
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.convert.MappingMongoConverter
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver
import org.springframework.data.mongodb.core.mapping.MongoMappingContext
import tools.jackson.databind.json.JsonMapper
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@DisplayName("Profile picture visibility Tests")
class ProfilePicVisibilityTest {

    private val owner = ObjectId()
    private val viewer = ObjectId()

    private val httpMapper = JsonMapper.builder().findAndAddModules().build()

    // Same conversions as the app, without a database
    private val converter: MappingMongoConverter = run {
        val conversions = MongoConfig().customConversions()
        val context = MongoMappingContext().apply {
            setSimpleTypeHolder(conversions.simpleTypeHolder)
            afterPropertiesSet()
        }
        MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context).apply {
            setCustomConversions(conversions)
            afterPropertiesSet()
        }
    }

    private fun ProfilePicVisibility.allowsFor(
        status: FriendshipStatus?,
        requester: ObjectId? = null,
        sharesGroup: Boolean = false,
    ): Boolean = allows(owner, status, requester, sharesGroup = { sharesGroup })

    @Test
    fun `accepted friends always see the picture`() {
        ProfilePicVisibility.entries.forEach {
            assertTrue(it.allowsFor(FriendshipStatus.ACCEPTED, requester = viewer), "$it")
        }
    }

    @Test
    fun `strangers see only a public picture or one shared through a group`() {
        assertTrue(PUBLIC.allowsFor(null))
        assertFalse(FRIENDS_AND_GROUPS.allowsFor(null))
        assertTrue(FRIENDS_AND_GROUPS.allowsFor(null, sharesGroup = true))
        assertFalse(FRIENDS.allowsFor(null, sharesGroup = true))
    }

    @Test
    fun `a pending request unlocks the sender's picture for its receiver only`() {
        // Owner sent the request: the viewer (receiver) sees it whatever the owner chose
        ProfilePicVisibility.entries.forEach {
            assertTrue(it.allowsFor(FriendshipStatus.PENDING, requester = owner), "$it")
        }

        // Viewer sent the request: the owner's setting decides as for a stranger
        assertTrue(PUBLIC.allowsFor(FriendshipStatus.PENDING, requester = viewer))
        assertFalse(FRIENDS_AND_GROUPS.allowsFor(FriendshipStatus.PENDING, requester = viewer))
        assertTrue(FRIENDS_AND_GROUPS.allowsFor(FriendshipStatus.PENDING, requester = viewer, sharesGroup = true))
        assertFalse(FRIENDS.allowsFor(FriendshipStatus.PENDING, requester = viewer, sharesGroup = true))
    }

    @Test
    fun `declined and blocked rows unlock nothing, public and shared groups still do`() {
        listOf(FriendshipStatus.DECLINED, FriendshipStatus.BLOCKED).forEach { status ->
            listOf(owner, viewer).forEach { requester ->
                assertTrue(PUBLIC.allowsFor(status, requester), "$status by $requester")
                assertFalse(FRIENDS_AND_GROUPS.allowsFor(status, requester), "$status by $requester")
                assertTrue(FRIENDS_AND_GROUPS.allowsFor(status, requester, sharesGroup = true), "$status by $requester")
                assertFalse(FRIENDS.allowsFor(status, requester, sharesGroup = true), "$status by $requester")
            }
        }
    }

    @Test
    fun `the group check only runs when the answer depends on it`() {
        var asked = false
        PUBLIC.allows(owner, null, null) { asked = true; true }
        FRIENDS.allows(owner, null, null) { asked = true; true }
        FRIENDS_AND_GROUPS.allows(owner, FriendshipStatus.ACCEPTED, viewer) { asked = true; true }
        assertFalse(asked)
    }

    @Test
    fun `stored settings without the field read as friends and groups`() {
        val stored = Document(mapOf("mdFormat" to false, "theme" to "DARK"))
        val settings = converter.read(PersonalUserSettings::class.java, stored)

        assertEquals(FRIENDS_AND_GROUPS, settings.profilePicVisibility)
        assertFalse(settings.mdFormat)
    }

    @Test
    fun `the setting is stored and read by its name`() {
        val doc = Document()
        converter.write(PersonalUserSettings(profilePicVisibility = FRIENDS), doc)

        assertEquals("FRIENDS", doc["profilePicVisibility"])
        assertEquals(FRIENDS, converter.read(PersonalUserSettings::class.java, doc).profilePicVisibility)
    }

    @Test
    fun `settings request takes the value by name and rejects unknown ones`() {
        val request = httpMapper.readValue("""{"profilePicVisibility":"PUBLIC"}""", UserController.UserSettingsRequest::class.java)
        assertEquals(PUBLIC, request.profilePicVisibility)

        val empty = httpMapper.readValue("""{}""", UserController.UserSettingsRequest::class.java)
        assertNull(empty.profilePicVisibility)

        assertThrows(Exception::class.java) {
            httpMapper.readValue("""{"profilePicVisibility":"EVERYONE"}""", UserController.UserSettingsRequest::class.java)
        }
    }

    @Test
    fun `a hidden picture sends a null timestamp, a visible one the real one`() {
        val picAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val user = User(
            id = owner,
            username = "owner",
            hashedPassword = "x",
            email = "owner@example.com",
            userDescription = "",
            userStatus = "",
            birthDate = "2000-01-01",
            createdAt = picAt,
            updatedAt = picAt,
            profilePicUpdatedAt = picAt,
        )

        val hidden = user.toSimpleUserResponse(FriendshipStatus.BLOCKED, viewer, profilePicVisible = false)
        val visible = user.toSimpleUserResponse(FriendshipStatus.BLOCKED, viewer, profilePicVisible = true)
        val friend = user.toFriendUserResponse(viewer, null, false, false, false, false)

        assertNull(hidden.profilePicUpdatedAt)
        assertEquals(picAt.toEpochMilliseconds(), visible.profilePicUpdatedAt)
        assertEquals(picAt.toEpochMilliseconds(), friend.profilePicUpdatedAt)

        // Sent as an explicit null, not left out
        assertTrue(httpMapper.writeValueAsString(hidden).contains("\"profilePicUpdatedAt\":null"))
    }
}
