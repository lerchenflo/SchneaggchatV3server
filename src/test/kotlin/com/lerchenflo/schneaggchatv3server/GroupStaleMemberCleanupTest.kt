@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.group.model.Group
import com.lerchenflo.schneaggchatv3server.group.model.GroupMember
import com.lerchenflo.schneaggchatv3server.repository.GroupMemberRepository
import com.lerchenflo.schneaggchatv3server.repository.GroupRepository
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Runs against a real Mongo (scripts/docker-test/run.sh unit): proves that
 * [GroupLookupService.isUserInGroup] ignores a member row left in a deleted group (E2E plan F15).
 * The startup cleanup this test also covered was removed once it ran on production.
 */
@SpringBootTest
class GroupStaleMemberCleanupTest {

    @Autowired lateinit var groupLookupService: GroupLookupService
    @Autowired lateinit var groupRepository: GroupRepository
    @Autowired lateinit var groupMemberRepository: GroupMemberRepository

    private val createdGroups = mutableListOf<ObjectId>()

    @AfterEach
    fun cleanUp() {
        groupMemberRepository.deleteAll(groupMemberRepository.findAllByGroupIdIn(createdGroups))
        groupRepository.deleteAllById(createdGroups)
        createdGroups.clear()
    }

    @Test
    fun `a member row of a deleted group is no membership`() {
        val alice = ObjectId()

        val deletedGroup = group(deleted = true)
        val liveGroup = group(deleted = false)

        member(alice, deletedGroup)
        member(alice, liveGroup)

        assertTrue(groupLookupService.isUserInGroup(alice, liveGroup))
        assertFalse(groupLookupService.isUserInGroup(alice, deletedGroup), "a stale row of a deleted group is no membership")
    }

    private fun group(deleted: Boolean): ObjectId {
        val now = Clock.System.now()
        val group = groupRepository.save(
            Group(name = "cleanup test", description = "", updatedAt = now, deleted = deleted, createdAt = now, creatorId = ObjectId())
        )
        createdGroups += group.id
        return group.id
    }

    private fun member(userId: ObjectId, groupId: ObjectId) {
        groupMemberRepository.save(
            GroupMember(userid = userId, groupId = groupId, joinedAt = Clock.System.now(), admin = false, color = 0)
        )
    }
}
