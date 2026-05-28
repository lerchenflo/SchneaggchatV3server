@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.core

import com.lerchenflo.schneaggchatv3server.core.security.HashEncoder
import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.group.GroupService
import com.lerchenflo.schneaggchatv3server.group.model.Group
import com.lerchenflo.schneaggchatv3server.repository.GroupRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.SchneaggmapService
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate
import org.springframework.data.mongodb.core.aggregation.SetOperation
import org.springframework.data.mongodb.core.index.IndexInfo
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.time.ExperimentalTime

/**
 * MainController for Ping etc.
 */

@RestController
class MainController(
    private val userLookupService: UserLookupService,
    private val groupLookupService: GroupLookupService,

    private val userService: UserService,

    private val hashEncoder: HashEncoder,
    private val mongoTemplate: MongoTemplate,
    private val groupService: GroupService,
    private val groupRepository: GroupRepository,


    private val schneaggmapService: SchneaggmapService,

    ){

    @GetMapping("/public/test")
    fun test(): String {
        return "Up and running!"
    }

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        migrateDBs()
        val testaccount = userService.ensureTestaccount()
        schneaggmapService.importLegacyMapEntries(testaccount.id)

        //listMongoIndexes()
        //printAllGroups()
    }


    fun listMongoIndexes() {
        val collections = mongoTemplate.db.listCollectionNames().toList()

        AppLogger.info("========= MongoDB Index Report =========")
        for (collection in collections) {
            AppLogger.info("Collection: $collection")

            val indexOps = mongoTemplate.indexOps(collection)
            val indexInfos: List<IndexInfo> = indexOps.indexInfo

            if (indexInfos.isEmpty()) {
                AppLogger.info("  -> No indexes found")
            } else {
                indexInfos.forEach { index ->
                    AppLogger.debug("  -> Index: ${index.name}")
                    AppLogger.debug("     Keys: ${index.indexFields}")
                    AppLogger.debug("     Unique: ${index.isUnique}")
                    AppLogger.debug("     Sparse: ${index.isSparse}")
                }
            }
            AppLogger.info("----------------------------------------")
        }
        AppLogger.info("========================================")
    }


    fun migrateDBs() {
        AppLogger.info("Running database migrations...")

        val query = Query()
        query.addCriteria(Criteria.where("profilePicUpdatedAt").exists(false))

        // Use SetOperation to reference the updatedAt field
        val update = AggregationUpdate.update()
            .set(
                SetOperation.builder()
                .set("profilePicUpdatedAt")
                .toValueOf("updatedAt")
            )

        val resultUsers = mongoTemplate.updateMulti(
            query,
            update,
            User::class.java
        )

        val resultGroups = mongoTemplate.updateMulti(
            query,
            update,
            Group::class.java
        )

        if (resultUsers.modifiedCount > 0) {
            AppLogger.success("Migration completed: Added profilePicUpdatedAt field to ${resultUsers.modifiedCount} users")
        } else {
            AppLogger.success("Migration check: All users already have a profilePicUpdatedAt field")
        }

        if (resultGroups.modifiedCount > 0) {
            AppLogger.success("Migration completed: Added profilePicUpdatedAt field to ${resultGroups.modifiedCount} groups")
        } else {
            AppLogger.success("Migration check: All groups already have a profilePicUpdatedAt field")
        }


    }



    /**
     * Prints all groups with their members, creator, and admin status in a table format.
     * ★ = Creator, ☆ = Admin (non-creator)
     */
    fun printAllGroups() {
        val groups = groupRepository.findAll()

        if (groups.isEmpty()) {
            AppLogger.info("No groups found.")
            return
        }

        println("\n╔════════════════════════════════════════════════════════════════════════════════╗")
        println("║                              ALL GROUPS REPORT                                 ║")
        println("╠════════════════════════════════════════════════════════════════════════════════╣")

        groups.forEach { group ->
            val members = groupLookupService.getGroupMembers(group.id)
            val creatorId = group.creatorId

            AppLogger.info("║                                                                                ║")
            AppLogger.info("║  Group: ${group.name.padEnd(68)}║")
            AppLogger.info("║  Description: ${group.description.take(60).padEnd(62)}║")
            AppLogger.info("║  ID: ${group.id.toHexString().padEnd(71)}║")
            AppLogger.info("╟────────────────────────────────────────────────────────────────────────────────╢")
            AppLogger.info("║  Members:                                                                      ║")
            AppLogger.info("║  ┌──────────────────────────────────┬──────────────┬──────────────────────────┐║")
            AppLogger.info("║  │ Username                         │ Role         │ User ID                  │║")
            AppLogger.info("║  ├──────────────────────────────────┼──────────────┼──────────────────────────┤║")

            members.forEach { member ->
                val username = userLookupService.getUsername(member.userid)
                val isCreator = member.userid == creatorId
                val isAdmin = member.admin

                val roleMarker = when {
                    isCreator -> "★ Creator"
                    isAdmin -> "☆ Admin"
                    else -> "  Member"
                }

                val displayName = username.take(30).padEnd(32)
                val roleDisplay = roleMarker.padEnd(12)
                val userIdShort = member.userid.toHexString().take(24).padEnd(24)

                AppLogger.info("║  │ $displayName │ $roleDisplay │ $userIdShort │║")
            }

            AppLogger.info("║  └──────────────────────────────────┴──────────────┴──────────────────────────┘║")
            AppLogger.info("║  Total members: ${members.size.toString().padEnd(60)}║")
            AppLogger.info("╠════════════════════════════════════════════════════════════════════════════════╣")
        }

        AppLogger.info("║  Total groups: ${groups.size.toString().padEnd(61)}║")
        AppLogger.info("╚════════════════════════════════════════════════════════════════════════════════╝\n")
    }


}