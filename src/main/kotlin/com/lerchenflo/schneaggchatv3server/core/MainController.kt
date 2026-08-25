@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.core

import com.lerchenflo.schneaggchatv3server.core.security.HashEncoder
import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.group.GroupService
import com.lerchenflo.schneaggchatv3server.group.model.Group
import com.lerchenflo.schneaggchatv3server.message.messagemodel.Message
import com.lerchenflo.schneaggchatv3server.repository.GroupRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.SchneaggmapService
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.user.usermodel.PersonalUserSettings
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.SyncCollection
import com.lerchenflo.schneaggchatv3server.util.VersionCounterService
import com.mongodb.MongoNamespace
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate
import org.springframework.data.mongodb.core.aggregation.SetOperation
import org.springframework.data.mongodb.core.index.IndexInfo
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.time.Clock
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

    private val versionCounterService: VersionCounterService,

    @Value("\${apns.debug}") private val debug: Boolean,

    ){

    @GetMapping("/public/test")
    fun test(): String {
        return "Up and running!"
    }

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        //migrateDBs()
        renameTypoCollections()
        migrateTypeAliases()
        migrateLastSeen()
        migratePersonalUserSettings()

        migrateReactionTimestamps()
        migrateMessageVersions()
        migrateMapAttributeKeys()

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
     * `User.lastSeen` was made non-nullable, defaulting to `updatedAt`. Documents written before
     * this field existed (or with it explicitly null) are backfilled here rather than relying on
     * the Kotlin default, since Mongo's `updateMulti` never deserializes the document into a `User`
     * instance - a missing/null `lastSeen` on a document read straight into a `User` object anywhere
     * else in the app would otherwise throw a mapping exception.
     */
    fun migrateLastSeen() {
        AppLogger.info("Running lastSeen migration...")

        val query = Query(
            Criteria().orOperator(
                Criteria.where("lastSeen").exists(false),
                Criteria.where("lastSeen").`is`(null)
            )
        )

        val update = AggregationUpdate.update()
            .set(
                SetOperation.builder()
                    .set("lastSeen")
                    .toValueOf("updatedAt")
            )

        val result = mongoTemplate.updateMulti(query, update, User::class.java)

        if (result.modifiedCount > 0) {
            AppLogger.success("Migration completed: Set lastSeen -> updatedAt for ${result.modifiedCount} users")
        } else {
            AppLogger.success("Migration check: All users already have a lastSeen field")
        }
    }

    /**
     * `User.settings` (PersonalUserSettings - theme, language, pinned chats, ...) is a new field.
     * Documents written before it existed get the default settings object here. Also bumps
     * `updatedAt` so every existing client picks this up on its next `/users/sync` and overwrites
     * its local (device-only, pre-sync) preferences with the server's defaults exactly once.
     *
     * `updatedAt` must be set to an actual `kotlin.time.Instant` value (as below), never via the
     * MongoDB-native `$currentDate` operator/`Update.currentDate(...)` - that writes a raw BSON
     * Date, bypassing Spring's conversion entirely, whereas every Instant field in this app is
     * stored as a `{epochSeconds, nanosecondsOfSecond}` subdocument. A raw Date is unreadable back
     * into `User.updatedAt` (ConverterNotFoundException) - see [repairCorruptedUpdatedAt].
     */
    fun migratePersonalUserSettings() {
        AppLogger.info("Running personal user settings migration...")

        val query = Query(Criteria.where("settings").exists(false))

        val update = Update()
            .set("settings", PersonalUserSettings())
            .set("updatedAt", Clock.System.now())

        val result = mongoTemplate.updateMulti(query, update, User::class.java)

        if (result.modifiedCount > 0) {
            AppLogger.success("Migration completed: Added default settings to ${result.modifiedCount} users")
        } else {
            AppLogger.success("Migration check: All users already have a settings field")
        }
    }

    /**
     * Backfills `reactedAt` on all embedded reaction subdocuments that were created before
     * the field was introduced. Sets `reactedAt` to the parent message's `sendDate` so existing
     * reactions get a sensible default timestamp.
     *
     * Uses `$map` + `$mergeObjects` + `$ifNull` inside an `AggregationExpression` because
     * Spring Data's `SetOperation` DSL doesn't expose `$map` natively, but `toValueOf` accepts
     * any `AggregationExpression`, keeping the query/update/result handling idiomatic.
     */
    fun migrateReactionTimestamps() {
        AppLogger.info("Running reaction timestamp migration...")

        val query = Query(
            Criteria.where("reactions.reactedAt").exists(false)
        )

        val mapExpression = org.springframework.data.mongodb.core.aggregation.AggregationExpression { _ ->
            org.bson.Document(
                "\$map", org.bson.Document()
                    .append("input", "\$reactions")
                    .append("as", "r")
                    .append(
                        "in", org.bson.Document(
                            "\$mergeObjects", listOf(
                                "\$\$r",
                                org.bson.Document(
                                    "reactedAt",
                                    org.bson.Document("\$ifNull", listOf("\$\$r.reactedAt", "\$sendDate"))
                                )
                            )
                        )
                    )
            )
        }

        val update = AggregationUpdate.update()
            .set(
                SetOperation.builder()
                    .set("reactions")
                    .toValueOf(mapExpression)
            )

        val result = mongoTemplate.updateMulti(query, update, "messages")

        if (result.modifiedCount > 0) {
            AppLogger.success("Migration completed: Backfilled reactedAt on reactions in ${result.modifiedCount} messages")
        } else {
            AppLogger.success("Migration check: All reactions already have a reactedAt field")
        }
    }

    /**
     * Backfills `Message.version` for documents written before version-based sync existed
     * (see `MessageService.messageSync` / `docs/CLIENT_SYNC_MIGRATION.md`). Reserves one
     * contiguous block of versions and assigns them in `sendDate` ascending order, so historical
     * message order matches version order for a client's very first version-based sync. No-op
     * once every message already has a `version`.
     */
    fun migrateMessageVersions() {
        AppLogger.info("Running message version migration...")

        val query = Query(Criteria.where("version").exists(false))
            .with(Sort.by(Sort.Direction.ASC, "sendDate"))

        val messagesMissingVersion = mongoTemplate.find(query, Message::class.java)

        if (messagesMissingVersion.isEmpty()) {
            AppLogger.success("Migration check: All messages already have a version field")
            return
        }

        val versions = versionCounterService.reserve(SyncCollection.MESSAGES, messagesMissingVersion.size)

        val bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, Message::class.java)
        messagesMissingVersion.forEachIndexed { index, message ->
            bulkOps.updateOne(
                Query(Criteria.where("_id").`is`(message.id)),
                Update().set("version", versions.first + index),
            )
        }
        val result = bulkOps.execute()

        AppLogger.success("Migration completed: Assigned version to ${result.modifiedCount} messages")
    }

    /**
     * One-time fix for the "frienships" collection name typo -> "friendships".
     * Renaming preserves all documents and indexes. No-op once already renamed.
     *
     * Spring's auto-index-creation (spring.data.mongodb.auto-index-creation=true) runs during
     * context startup - BEFORE this ApplicationReadyEvent listener - and will have already
     * created an empty "friendships" collection (with its indexes) from the updated @Document
     * annotation. That pre-existing empty collection must not block the rename.
     */
    fun renameTypoCollections() {
        val db = mongoTemplate.db
        val existingCollections = db.listCollectionNames().toSet()

        if (!existingCollections.contains("frienships")) {
            return // Already renamed (or never existed on a fresh install)
        }

        if (existingCollections.contains("friendships")) {
            val targetCount = db.getCollection("friendships").countDocuments()
            if (targetCount > 0) {
                AppLogger.warn("Migration skipped: 'friendships' already has $targetCount document(s) - refusing to overwrite. Resolve 'frienships' vs 'friendships' manually.")
                return
            }
            // Empty collection auto-created by Spring's index creation - safe to drop before the rename.
            db.getCollection("friendships").drop()
        }

        db.getCollection("frienships").renameCollection(MongoNamespace(db.name, "friendships"))
        AppLogger.success("Migration completed: Renamed collection 'frienships' -> 'friendships'")
    }

    /**
     * Every @Document entity now declares a short @TypeAlias instead of relying on Spring's
     * default behaviour of storing the fully-qualified class name in '_class'. Documents written
     * before the alias was added still have the old FQCN in '_class' - rewrite those so old and
     * new documents are consistent (and so future class renames/moves don't break old data).
     */
    fun migrateTypeAliases() {
        AppLogger.info("Running type alias migration...")

        data class AliasMigration(val collection: String, val oldClassName: String, val newAlias: String)

        val migrations = listOf(
            AliasMigration("users", "com.lerchenflo.schneaggchatv3server.user.usermodel.User", "user"),
            AliasMigration("messages", "com.lerchenflo.schneaggchatv3server.message.messagemodel.Message", "message"),
            AliasMigration("groups", "com.lerchenflo.schneaggchatv3server.group.model.Group", "group"),
            AliasMigration("groupmembers", "com.lerchenflo.schneaggchatv3server.group.model.GroupMember", "groupmember"),
            AliasMigration("apnstokens", "com.lerchenflo.schneaggchatv3server.notifications.apns.model.ApnsToken", "apnstoken"),
            AliasMigration("firebasetokens", "com.lerchenflo.schneaggchatv3server.notifications.firebase.model.FirebaseToken", "firebasetoken"),
            AliasMigration("friendships", "com.lerchenflo.schneaggchatv3server.user.friendshipmodel.Friendship", "friendship"),
            AliasMigration("friendship_settings", "com.lerchenflo.schneaggchatv3server.user.friendshipmodel.FriendshipSetting", "friendshipsetting"),
            AliasMigration("refreshTokens", "com.lerchenflo.schneaggchatv3server.authentication.model.RefreshToken", "refreshtoken"),
            AliasMigration("userlocations", "com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.UserLocation", "userlocation"),
            AliasMigration("logs", "com.lerchenflo.schneaggchatv3server.util.Log", "log"),
        )

        for (migration in migrations) {
            val result = mongoTemplate.updateMulti(
                Query(Criteria.where("_class").`is`(migration.oldClassName)),
                Update.update("_class", migration.newAlias),
                migration.collection
            )

            if (result.modifiedCount > 0) {
                AppLogger.success("Migration completed: Updated _class -> '${migration.newAlias}' for ${result.modifiedCount} documents in '${migration.collection}'")
            }
        }

        AppLogger.success("Type alias migration check complete")
    }

    /**
     * Renames map entry attribute fields inside `map_entries.locationData[]` so the Kotlin property,
     * the AttributeKey enum constant, the JSON wire key and the Mongo field name are all the same
     * string (e.g. "speedLimit" -> "radarSpeedLimit"). Must run before
     * [SchneaggmapService.importLegacyMapEntries] / any typed [MapEntry] read - the new
     * `LocationData` subtypes no longer have the old field names, so deserializing an un-migrated
     * document would fail on a missing required property.
     *
     * `$rename` cannot address fields inside array elements, so this walks each document as a raw
     * BSON `Document` by hand instead of an aggregation update. Idempotent per-field (not just
     * per-document), so an interrupted previous run is finished correctly on the next startup.
     */
    fun migrateMapAttributeKeys() {
        AppLogger.info("Running map attribute key migration...")

        data class Rename(val typeAlias: String, val old: String, val new: String)

        val renames = listOf(
            Rename("radar", "speedLimit", "radarSpeedLimit"),
            Rename("radar", "mobile", "radarMobile"),
            Rename("radar", "redLight", "radarRedLight"),
            Rename("police", "lastSeen", "policeLastSeen"),
            Rename("mountain_street", "mautFee", "mountainStreetMautFee"),
            Rename("mountain_street", "heightLimit", "mountainStreetHeightLimit"),
            Rename("mountain_street", "closedInWinter", "mountainStreetClosedInWinter"),
            Rename("wheeliespot", "onlyOnWeekends", "wheeliespotOnlyOnWeekends"),
            Rename("offroad_motorcycle", "legal", "offroadMotorcycleLegal"),
            Rename("offroad_motorcycle", "motocross", "offroadMotorcycleMotocross"),
            Rename("offroad_motorcycle", "enduro", "offroadMotorcycleEnduro"),
            Rename("viewpoint", "lieDownFriendly", "viewpointLieDownFriendly"),
            Rename("camping", "official", "campingOfficial"),
            Rename("camping", "waterDistance", "campingWaterDistance"),
            Rename("camping", "sittingPossibility", "campingSittingPossibility"),
            Rename("camping", "grillPossibility", "campingGrillPossibility"),
            Rename("swimming", "indoor", "swimmingIndoor"),
            Rename("swimming", "jumpSpot", "swimmingJumpSpot"),
            Rename("swimming", "lieDownFriendly", "swimmingLieDownFriendly"),
            Rename("swimming", "price", "swimmingPrice"),
            Rename("climbingspot", "viaFerrata", "climbingspotViaFerrata"),
            Rename("climbingspot", "outdoor", "climbingspotOutdoor"),
            Rename("climbingspot", "price", "climbingspotPrice"),
            Rename("volleyball", "goodNet", "volleyballGoodNet"),
            Rename("volleyball", "goodField", "volleyballGoodField"),
            Rename("volleyball", "outdoor", "volleyballOutdoor"),
            Rename("bicycle", "legal", "bicycleLegal"),
            Rename("bicycle", "difficulty", "bicycleDifficulty"),
            Rename("bicycle", "undergroundType", "bicycleUndergroundType"),
            Rename("outdoor_fitness", "shadow", "outdoorFitnessShadow"),
            Rename("table_tennis", "private", "tableTennisPrivate"),
            Rename("tennis", "paddle", "tennisPaddle"),
            Rename("sightseeing", "entryFee", "sightseeingEntryFee"),
            Rename("party", "entryFee", "partyEntryFee"),
            Rename("wifi", "ssid", "wifiSsid"),
            Rename("wifi", "password", "wifiPassword"),
            Rename("food_kebab", "kebabPrice", "foodKebabPrice"),
            Rename("food_pizza", "margaritaPrice", "foodPizzaMargaritaPrice"),
            Rename("food_burger", "cheeseburgerPrice", "foodBurgerCheeseburgerPrice"),
            Rename("food_beer", "beerPrice", "foodBeerPrice"),
            Rename("food_ice", "iceScoopPrice", "foodIceScoopPrice"),
            Rename("food_cafe_bakery", "outdoorSeating", "foodCafeBakeryOutdoorSeating"),
            Rename("food_cafe_bakery", "alcohol", "foodCafeBakeryAlcohol"),
            Rename("food_cafe_bakery", "coffee", "foodCafeBakeryCoffee"),
            Rename("food_cafe_bakery", "breakfast", "foodCafeBakeryBreakfast"),
            Rename("food_asian", "allYouCanEat", "foodAsianAllYouCanEat"),
            Rename("food_other", "cuisine", "foodOtherCuisine"),
        )
        val renamesByTypeAlias = renames.groupBy { it.typeAlias }

        val collection = mongoTemplate.getCollection("map_entries")
        var modifiedDocuments = 0

        collection.find().forEach { doc ->
            val locationDataList = doc.getList("locationData", org.bson.Document::class.java) ?: return@forEach
            var changed = false

            locationDataList.forEach { element ->
                val typeAlias = element.getString("_class") ?: return@forEach
                val fieldRenames = renamesByTypeAlias[typeAlias] ?: return@forEach
                for ((_, old, new) in fieldRenames) {
                    if (element.containsKey(old) && !element.containsKey(new)) {
                        element[new] = element.remove(old)
                        changed = true
                    }
                }
            }

            if (changed) {
                collection.replaceOne(org.bson.Document("_id", doc.getObjectId("_id")), doc)
                modifiedDocuments++
            }
        }

        if (modifiedDocuments > 0) {
            AppLogger.success("Migration completed: Renamed map attribute keys in $modifiedDocuments map entries")
        } else {
            AppLogger.success("Migration check: All map attribute keys already migrated")
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