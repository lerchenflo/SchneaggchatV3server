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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Runs against a real Mongo (scripts/docker-test/run.sh unit): proves the startup cleanup
 * [GroupLookupService.deleteMembersOfDeletedGroups] removes only member rows of deleted groups, and
 * that [GroupLookupService.isUserInGroup] ignores a deleted group (E2E plan F15).
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
    fun `deletes only the member rows of deleted groups`() {
        val alice = ObjectId()
        val bob = ObjectId()
        val carol = ObjectId()

        // Same users in a deleted and a live group: only the deleted group's rows may go
        val deletedGroup = group(deleted = true)
        val liveGroup = group(deleted = false)
        val otherLiveGroup = group(deleted = false)
        group(deleted = true) // deleted group without member rows

        member(alice, deletedGroup)
        member(bob, deletedGroup)
        member(alice, liveGroup)
        member(bob, liveGroup)
        member(carol, liveGroup)
        member(carol, otherLiveGroup)

        assertTrue(groupLookupService.isUserInGroup(alice, liveGroup))
        assertFalse(groupLookupService.isUserInGroup(alice, deletedGroup), "a stale row of a deleted group is no membership")

        val deletedRows = groupLookupService.deleteMembersOfDeletedGroups()

        assertTrue(deletedRows >= 2, "both stale rows deleted, got $deletedRows")
        assertEquals(emptyList(), groupMemberRepository.findAllByGroupId(deletedGroup))
        assertEquals(setOf(alice, bob, carol), groupMemberRepository.findAllByGroupId(liveGroup).map { it.userid }.toSet())
        assertEquals(listOf(carol), groupMemberRepository.findAllByGroupId(otherLiveGroup).map { it.userid })
        assertTrue(groupLookupService.isUserInGroup(alice, liveGroup))
        assertTrue(groupLookupService.isUserInGroup(carol, otherLiveGroup))

        // Second run (next startup) finds nothing of these groups and deletes nothing of them
        groupLookupService.deleteMembersOfDeletedGroups()
        assertEquals(3, groupMemberRepository.findAllByGroupId(liveGroup).size)
        assertEquals(1, groupMemberRepository.findAllByGroupId(otherLiveGroup).size)
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
