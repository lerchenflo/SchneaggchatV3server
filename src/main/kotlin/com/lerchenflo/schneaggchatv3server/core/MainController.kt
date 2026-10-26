package com.lerchenflo.schneaggchatv3server.core

import com.lerchenflo.schneaggchatv3server.authentication.model.RefreshToken
import com.lerchenflo.schneaggchatv3server.feedback.FeedbackSeedService
import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.repository.GroupRepository
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.*
import com.lerchenflo.schneaggchatv3server.website.faq.FaqSeedService
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.index.IndexInfo
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * MainController for Ping etc.
 */

@RestController
class MainController(
    private val userLookupService: UserLookupService,
    private val groupLookupService: GroupLookupService,

    private val userService: UserService,

    private val mongoTemplate: MongoTemplate,
    private val groupRepository: GroupRepository,

    private val faqSeedService: FaqSeedService,

    private val feedbackSeedService: FeedbackSeedService,

    @Value("\${apns.debug}") private val debug: Boolean,

    ){

    @GetMapping("/public/test")
    fun test(): String {
        return "Up and running!"
    }

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        ensureRefreshTokenIndexes()

        faqSeedService.seedMissingEntries()
        feedbackSeedService.seedMissingEntries()

        if (debug) {
            //Create test account for google play & Apple
            val testaccount = userService.ensureTestaccount()
        }

        //listMongoIndexes()
        //printAllGroups()


        AppLogger.success("Server started successfully")

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


    /**
     * The `refreshTokens` lookup indexes (see [RefreshToken]). Created here rather than via
     * annotations; `createIndex` is a no-op when the index already exists.
     */
    fun ensureRefreshTokenIndexes() {
        val indexOps = mongoTemplate.indexOps(RefreshToken::class.java)
        indexOps.createIndex(
            Index().named("user_current_token")
                .on("userId", Sort.Direction.ASC)
                .on("hashedToken", Sort.Direction.ASC)
        )
        indexOps.createIndex(
            Index().named("user_previous_token")
                .on("userId", Sort.Direction.ASC)
                .on("previousHashedToken", Sort.Direction.ASC)
        )
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

        AppLogger.info("╔════════════════════════════════════════════════════════════════════════════════╗")
        AppLogger.info("║                              ALL GROUPS REPORT                                 ║")
        AppLogger.info("╠════════════════════════════════════════════════════════════════════════════════╣")

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
