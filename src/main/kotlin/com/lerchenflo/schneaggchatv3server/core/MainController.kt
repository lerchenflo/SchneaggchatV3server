package com.lerchenflo.schneaggchatv3server.core

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.core.type.TypeReference
import com.lerchenflo.schneaggchatv3server.authentication.model.RefreshToken
import com.lerchenflo.schneaggchatv3server.events.eventmodel.Event
import com.lerchenflo.schneaggchatv3server.feedback.FeedbackSeedService
import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.group.model.Group
import com.lerchenflo.schneaggchatv3server.message.messagemodel.Message
import com.lerchenflo.schneaggchatv3server.repository.DonationRepository
import com.lerchenflo.schneaggchatv3server.repository.GroupRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.LocationData
import com.lerchenflo.schneaggchatv3server.schneaggmap.SchneaggmapService
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.BicycleUndergroundType
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntry
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.user.usermodel.PersonalUserSettings
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import com.lerchenflo.schneaggchatv3server.user.usermodel.UserRole
import com.lerchenflo.schneaggchatv3server.util.*
import com.lerchenflo.schneaggchatv3server.website.donations.model.Donation
import com.lerchenflo.schneaggchatv3server.website.faq.FaqSeedService
import com.mongodb.MongoNamespace
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate
import org.springframework.data.mongodb.core.aggregation.SetOperation
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.index.IndexInfo
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

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

    private val versionCounterService: VersionCounterService,

    private val donationRepository: DonationRepository,

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
        //migrateDBs()
        renameTypoCollections()
        migrateTypeAliases()
        migrateLastSeen()
        migratePersonalUserSettings()
        migrateContributePopupShown()

        migrateReactionTimestamps()
        migrateMessageVersions()
        migrateMapAttributeKeys()
        migrateMapVersionTypeIds()
        migrateMapAttributeTypes()
        migrateGroupDeletedFlag()
        migrateDetachEventsFromDeletedGroups()
        migrateUserRole()
        migrateEmailNormalization()
        migrateRefreshTokenChains()
        migrateDonations()

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
     * `PersonalUserSettings.lastContributePopupShown` is a new field. Accounts created before it
     * existed would otherwise read as `0` (never shown), making the contribute popup due
     * immediately for every existing user. Backfilled here to the current time instead, same as a
     * device seeding it locally on first launch. Deliberately does NOT bump `User.updatedAt` -
     * unlike [migratePersonalUserSettings], this shouldn't force every account through
     * `/users/sync` again or clobber a device's own already-seeded local timestamp; devices adopt
     * this value the next time their settings actually change.
     */
    fun migrateContributePopupShown() {
        AppLogger.info("Running contribute popup timestamp migration...")

        val query = Query(Criteria.where("settings.lastContributePopupShown").exists(false))

        val update = Update()
            .set("settings.lastContributePopupShown", Clock.System.now().toEpochMilliseconds())

        val result = mongoTemplate.updateMulti(query, update, User::class.java)

        if (result.modifiedCount > 0) {
            AppLogger.success("Migration completed: Set contribute popup timestamp for ${result.modifiedCount} users")
        } else {
            AppLogger.success("Migration check: All users already have a contribute popup timestamp")
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
     * `Group.deleted` was added with the expiring-groups feature. Documents written before that
     * have no `deleted` field at all, and Mongo does not match a missing field against `false`,
     * so `findByIdInAndDeletedFalse` silently hid every legacy group from /groups/sync - which
     * additionally made the sync report them as deleted to clients. Backfilled here once.
     */
    fun migrateGroupDeletedFlag() {
        AppLogger.info("Running group deleted-flag migration...")

        val query = Query(Criteria.where("deleted").exists(false))

        val result = mongoTemplate.updateMulti(
            query,
            Update().set("deleted", false),
            Group::class.java
        )

        if (result.modifiedCount > 0) {
            AppLogger.success("Migration completed: Set deleted=false on ${result.modifiedCount} groups")
        } else {
            AppLogger.success("Migration check: All groups already have a deleted field")
        }
    }

    /**
     * `User.role` is a new field powering the admin panel. Documents written before it existed have
     * no `role` at all, and Mongo does not match a missing field against a value, so a role query
     * would silently skip them. Backfilled here to USER.
     *
     * This migration deliberately never grants ADMIN - it only fills in the default. Usernames are
     * mutable (/users/changeusername), so promoting by username would be unsafe; admin access is
     * granted by hand directly in MongoDB (`db.users.updateOne({username:"..."}, {$set:{role:"ADMIN"}})`)
     * and survives every future run of this migration because it only touches documents missing the field.
     */
    fun migrateUserRole() {
        AppLogger.info("Running user role migration...")

        val query = Query(Criteria.where("role").exists(false))

        val result = mongoTemplate.updateMulti(
            query,
            Update().set("role", UserRole.USER.name),
            User::class.java
        )

        if (result.modifiedCount > 0) {
            AppLogger.success("Migration completed: Set role=USER on ${result.modifiedCount} users")
        } else {
            AppLogger.success("Migration check: All users already have a role field")
        }
    }

    /**
     * Stores every email in [ValidationUtils.normalizeEmail] form. Accounts from before registration
     * lowercased the address kept it as typed, so lookups by the normalized address missed them and
     * the duplicate check let a second account in with the same address in other case.
     */
    fun migrateEmailNormalization() {
        AppLogger.info("Running email normalization migration...")

        val query = Query().apply { fields().include("email") }
        val users = mongoTemplate.find(query, org.bson.Document::class.java, "users")
            .mapNotNull { doc -> doc.getString("email")?.let { doc.getObjectId("_id") to it } }

        var normalizedCount = 0
        users.forEach { (id, email) ->
            val normalized = ValidationUtils.normalizeEmail(email)
            if (normalized != email) {
                mongoTemplate.updateFirst(Query(Criteria.where("_id").`is`(id)), Update().set("email", normalized), "users")
                normalizedCount++
            }
        }

        if (normalizedCount > 0) {
            AppLogger.success("Migration completed: Normalized the email of $normalizedCount users")
        } else {
            AppLogger.success("Migration check: All emails already normalized")
        }
    }

    /**
     * One-time import of the donations that used to be hand-edited into `donations-data.js`, now
     * that donations live in the `donations` collection and are managed from the admin panel.
     * Guarded by a count check so it never re-inserts rows an admin has since deleted.
     */
    fun migrateDonations() {
        AppLogger.info("Running donations import migration...")

        if (donationRepository.count() > 0) {
            AppLogger.success("Migration check: donations collection already populated")
            return
        }

        fun vienna(year: Int, month: Int, day: Int) =
            java.time.LocalDate.of(year, month, day)
                .atStartOfDay(java.time.ZoneId.of("Europe/Vienna"))
                .toInstant()
                .toEpochMilli()
                .let { kotlin.time.Instant.fromEpochMilliseconds(it) }

        val now = Clock.System.now()
        val legacyDonations = listOf(
            Donation(name = "Petra", amountCents = 5000, donatedAt = vienna(2026, 1, 27), message = "Gratuliere", createdAt = now, updatedAt = now),
            Donation(name = "Jonny", amountCents = 500, donatedAt = vienna(2026, 1, 14), message = "Liebe Grüße", createdAt = now, updatedAt = now),
            Donation(name = "Daffith", amountCents = 1000, donatedAt = vienna(2025, 12, 24), message = "Weihnachtsspende", createdAt = now, updatedAt = now),
            Donation(name = "Herr Ess", amountCents = 2000, donatedAt = vienna(2025, 10, 14), message = "Schneaggchat", createdAt = now, updatedAt = now),
            Donation(name = "Norbert Konrad", amountCents = 2000, donatedAt = vienna(2025, 5, 28), message = "Dra bliba buaba...", createdAt = now, updatedAt = now),
        )

        donationRepository.saveAll(legacyDonations)
        AppLogger.success("Migration completed: Imported ${legacyDonations.size} legacy donations")
    }

    /**
     * Events used to be hard-deleted the moment their group was deleted, so no dangling groupId
     * could ever exist. Now that events can survive a deleted group, backfill any pre-existing
     * event still pointing at a soft-deleted (or missing) group by detaching it. No-op once clean.
     */
    fun migrateDetachEventsFromDeletedGroups() {
        AppLogger.info("Running event group-detach migration...")

        val liveGroupIds = mongoTemplate.findDistinct(
            Query(Criteria.where("deleted").ne(true)),
            "_id",
            Group::class.java,
            ObjectId::class.java
        )

        val query = Query(
            Criteria.where("groupId").exists(true).ne(null).nin(liveGroupIds)
        )

        val result = mongoTemplate.updateMulti(query, Update().set("groupId", null), Event::class.java)

        if (result.modifiedCount > 0) {
            AppLogger.success("Migration completed: Detached ${result.modifiedCount} events from deleted groups")
        } else {
            AppLogger.success("Migration check: No events pointed at a deleted group")
        }
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
                AppLogger.warn("Migration skipped: 'friendships' already has $targetCount document(s) - refusing to overwrite. Resolve 'frienships' vs 'friendships' manually.", tag = "migrate_friendships_skipped")
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
     * Migrates `refreshTokens` from the old soft-delete rotation model (every refresh inserted a
     * new row and kept the old one with `deletedAt` + `replacedByToken` until a cleanup sweep) to
     * the in-place rotation model: one row per device whose `hashedToken` is swapped on refresh
     * and whose `previousHashedToken` serves one-hop replay recovery (see [RefreshToken]).
     * Idempotent - after the first run every step is a no-op.
     */
    fun migrateRefreshTokenChains() {
        AppLogger.info("Running refresh token chain migration...")

        val collection = "refreshTokens"

        // 1) Preserve in-flight replay chains: a client still retrying with a rotated-away token
        // must be able to match on its successor's previousHashedToken once the old row is gone.
        // Raw Documents - the entity no longer has deletedAt/replacedByToken.
        val softDeleted = mongoTemplate.find(
            Query(Criteria.where("deletedAt").ne(null)),
            org.bson.Document::class.java,
            collection
        )

        var linkedCount = 0
        for (doc in softDeleted) {
            val successorId = doc.getObjectId("replacedByToken") ?: continue
            val oldHash = doc.getString("hashedToken") ?: continue

            val result = mongoTemplate.updateFirst(
                Query(
                    Criteria.where("_id").`is`(successorId)
                        .and("deletedAt").`is`(null)
                        .and("previousHashedToken").`is`(null)
                ),
                Update.update("previousHashedToken", oldHash),
                collection
            )
            linkedCount += result.modifiedCount.toInt()
        }

        // 2) The rotated-away rows themselves are obsolete under the new model
        val removedRotated = mongoTemplate.remove(Query(Criteria.where("deletedAt").ne(null)), collection)

        // 3) Establish the one-row-per-device invariant (login dedup) retroactively: keep only
        // the newest session row per (userId, deviceName, deviceType) - older duplicates are
        // leftovers of re-logins under the old model and inflated the active-device count.
        // Deliberately raw Documents, NOT the RefreshToken entity: a single legacy row with an
        // off-format field (e.g. a raw BSON Date where an Instant subdocument is expected - the
        // exact corruption [repairCorruptedUpdatedAt] exists for) would otherwise throw a
        // ConverterNotFoundException here and crash-loop every startup.
        fun createdAtSeconds(doc: org.bson.Document): Long = when (val v = doc["createdAt"]) {
            is org.bson.Document -> (v["epochSeconds"] as? Number)?.toLong() ?: 0L
            is java.util.Date -> v.time / 1000
            else -> 0L
        }

        val duplicateIds = mongoTemplate.find(
            Query(Criteria.where("deviceName").nin(listOf(null, ""))),
            org.bson.Document::class.java,
            collection
        )
            .groupBy { Triple(it["userId"], it.getString("deviceName"), it["deviceType"]) }
            .values
            .filter { it.size > 1 }
            .flatMap { rows -> rows.sortedByDescending { createdAtSeconds(it) }.drop(1) }
            .map { it["_id"] }

        if (duplicateIds.isNotEmpty()) {
            mongoTemplate.remove(Query(Criteria.where("_id").`in`(duplicateIds)), collection)
        }

        // 4) Drop the legacy fields from the surviving rows
        mongoTemplate.updateMulti(
            Query(),
            Update().unset("deletedAt").unset("replacedByToken"),
            collection
        )

        // 5) Index lifecycle: the old partial unique index makes no sense in the new model (its
        // {'deletedAt': null} filter now matches every row). Managed programmatically here rather
        // than via annotations so the drop/replace can't collide with
        // spring.data.mongodb.auto-index-creation at startup.
        val indexOps = mongoTemplate.indexOps(RefreshToken::class.java)
        if (indexOps.indexInfo.any { it.name == "active_user_token" }) {
            indexOps.dropIndex("active_user_token")
        }
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

        if (linkedCount > 0 || removedRotated.deletedCount > 0 || duplicateIds.isNotEmpty()) {
            AppLogger.success("Refresh token migration: linked $linkedCount replay chains, removed ${removedRotated.deletedCount} rotated rows and ${duplicateIds.size} duplicate device rows")
        } else {
            AppLogger.success("Refresh token migration check complete")
        }
    }

    /** One attribute field renamed by [migrateMapAttributeKeys], inside elements of type [typeAlias]. */
    private data class MapAttributeRename(val typeAlias: String, val old: String, val new: String)

    private val mapAttributeRenames = listOf(
        MapAttributeRename("radar", "speedLimit", "radarSpeedLimit"),
        MapAttributeRename("radar", "mobile", "radarMobile"),
        MapAttributeRename("radar", "redLight", "radarRedLight"),
        MapAttributeRename("police", "lastSeen", "policeLastSeen"),
        MapAttributeRename("mountain_street", "mautFee", "mountainStreetMautFee"),
        MapAttributeRename("mountain_street", "heightLimit", "mountainStreetHeightLimit"),
        MapAttributeRename("mountain_street", "closedInWinter", "mountainStreetClosedInWinter"),
        MapAttributeRename("wheeliespot", "onlyOnWeekends", "wheeliespotOnlyOnWeekends"),
        MapAttributeRename("offroad_motorcycle", "legal", "offroadMotorcycleLegal"),
        MapAttributeRename("offroad_motorcycle", "motocross", "offroadMotorcycleMotocross"),
        MapAttributeRename("offroad_motorcycle", "enduro", "offroadMotorcycleEnduro"),
        MapAttributeRename("viewpoint", "lieDownFriendly", "viewpointLieDownFriendly"),
        MapAttributeRename("camping", "official", "campingOfficial"),
        MapAttributeRename("camping", "waterDistance", "campingWaterDistance"),
        MapAttributeRename("camping", "sittingPossibility", "campingSittingPossibility"),
        MapAttributeRename("camping", "grillPossibility", "campingGrillPossibility"),
        MapAttributeRename("swimming", "indoor", "swimmingIndoor"),
        MapAttributeRename("swimming", "jumpSpot", "swimmingJumpSpot"),
        MapAttributeRename("swimming", "lieDownFriendly", "swimmingLieDownFriendly"),
        MapAttributeRename("swimming", "price", "swimmingPrice"),
        MapAttributeRename("climbingspot", "viaFerrata", "climbingspotViaFerrata"),
        MapAttributeRename("climbingspot", "outdoor", "climbingspotOutdoor"),
        MapAttributeRename("climbingspot", "price", "climbingspotPrice"),
        MapAttributeRename("volleyball", "goodNet", "volleyballGoodNet"),
        MapAttributeRename("volleyball", "goodField", "volleyballGoodField"),
        MapAttributeRename("volleyball", "outdoor", "volleyballOutdoor"),
        MapAttributeRename("bicycle", "legal", "bicycleLegal"),
        MapAttributeRename("bicycle", "difficulty", "bicycleDifficulty"),
        MapAttributeRename("bicycle", "undergroundType", "bicycleUndergroundType"),
        MapAttributeRename("outdoor_fitness", "shadow", "outdoorFitnessShadow"),
        MapAttributeRename("table_tennis", "private", "tableTennisPrivate"),
        MapAttributeRename("tennis", "paddle", "tennisPaddle"),
        MapAttributeRename("sightseeing", "entryFee", "sightseeingEntryFee"),
        MapAttributeRename("party", "entryFee", "partyEntryFee"),
        MapAttributeRename("wifi", "ssid", "wifiSsid"),
        MapAttributeRename("wifi", "password", "wifiPassword"),
        MapAttributeRename("food_kebab", "kebabPrice", "foodKebabPrice"),
        MapAttributeRename("food_pizza", "margaritaPrice", "foodPizzaMargaritaPrice"),
        MapAttributeRename("food_burger", "cheeseburgerPrice", "foodBurgerCheeseburgerPrice"),
        MapAttributeRename("food_beer", "beerPrice", "foodBeerPrice"),
        MapAttributeRename("food_ice", "iceScoopPrice", "foodIceScoopPrice"),
        MapAttributeRename("food_cafe_bakery", "outdoorSeating", "foodCafeBakeryOutdoorSeating"),
        MapAttributeRename("food_cafe_bakery", "alcohol", "foodCafeBakeryAlcohol"),
        MapAttributeRename("food_cafe_bakery", "coffee", "foodCafeBakeryCoffee"),
        MapAttributeRename("food_cafe_bakery", "breakfast", "foodCafeBakeryBreakfast"),
        MapAttributeRename("food_asian", "allYouCanEat", "foodAsianAllYouCanEat"),
        MapAttributeRename("food_other", "cuisine", "foodOtherCuisine"),
    )

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

        val renamesByTypeAlias = mapAttributeRenames.groupBy { it.typeAlias }

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
     * The change log wrote its `locationData` snapshots as a plain List, which leaves out each
     * element's `_class` type id (fixed in `MapEntryVersionService.locationDataJson`) - so they can
     * neither be shown nor reverted. Adds the type id, worked out from the element's field names,
     * and renames pre-[migrateMapAttributeKeys] field names on the way, so [migrateMapAttributeTypes]
     * (which skips elements without `_class`) converts their values next. Must run between those two.
     *
     * Some old field names were shared by several types (`legal`, `price`, `entryFee`, ...). Those
     * are decided by the types the entry has now; an element that still fits more than one type,
     * or none, is left as it is and logged. Idempotent - only elements without `_class` are touched.
     */
    fun migrateMapVersionTypeIds() {
        AppLogger.info("Running map change log type id migration...")

        // Every field name a type has, had before the key rename, or had before the type migration
        val fieldsByTypeAlias = mutableMapOf<String, MutableSet<String>>()
        LocationData::class.java.getAnnotation(JsonSubTypes::class.java).value.forEach { subType ->
            val properties = Json.mapper.serializationConfig
                .introspect(Json.mapper.constructType(subType.value.java))
                .findProperties()
                .map { it.name }
            fieldsByTypeAlias.getOrPut(subType.name) { mutableSetOf() } += properties
        }
        mapAttributeRenames.forEach { rename ->
            fieldsByTypeAlias.getOrPut(rename.typeAlias) { mutableSetOf() } += listOf(rename.old, rename.new)
        }
        val renamesByTypeAlias = mapAttributeRenames.groupBy { it.typeAlias }

        val entries = mongoTemplate.getCollection("map_entries")
        val currentTypesByEntry = mutableMapOf<ObjectId, Set<String>>()
        fun currentTypesOf(entryId: ObjectId) = currentTypesByEntry.getOrPut(entryId) {
            entries.find(org.bson.Document("_id", entryId)).first()
                ?.getList("locationData", org.bson.Document::class.java)
                ?.mapNotNull { it.getString("_class") }
                ?.toSet()
                ?: emptySet()
        }

        val versions = mongoTemplate.getCollection("map_entry_versions")
        var migratedVersions = 0
        var unresolvedElements = 0

        versions.find(org.bson.Document("changes.field", "locationData")).forEach { doc ->
            val changes = doc.getList("changes", org.bson.Document::class.java) ?: return@forEach
            var changed = false

            changes.filter { it.getString("field") == "locationData" }.forEach { change ->
                for (side in listOf("oldValue", "newValue")) {
                    val rawJson = change.getString(side) ?: continue
                    val elements = try {
                        Json.mapper.readValue(rawJson, object : TypeReference<List<MutableMap<String, Any?>>>() {})
                    } catch (e: Exception) {
                        AppLogger.warn("Map change log type id migration: skipping unreadable snapshot in version ${doc.getObjectId("_id")}: ${e.message}", tag = "migrate_map_version_type_id_skipped")
                        continue
                    }

                    var sideChanged = false
                    elements.filter { !it.containsKey("_class") }.forEach { element ->
                        val fields = element.keys
                        val candidates = fieldsByTypeAlias.filterValues { known -> fields.isNotEmpty() && known.containsAll(fields) }.keys
                        val typeAlias = candidates.singleOrNull()
                            ?: candidates.intersect(currentTypesOf(doc.getObjectId("entryId"))).singleOrNull()

                        if (typeAlias == null) {
                            AppLogger.warn("Map change log type id migration: can't tell the type of $fields in version ${doc.getObjectId("_id")} (fits ${candidates.ifEmpty { "none" }})", tag = "migrate_map_version_type_id_unresolved")
                            unresolvedElements++
                            return@forEach
                        }

                        renamesByTypeAlias[typeAlias]?.forEach { (_, old, new) ->
                            if (element.containsKey(old) && !element.containsKey(new)) element[new] = element.remove(old)
                        }
                        element["_class"] = typeAlias
                        sideChanged = true
                    }

                    if (sideChanged) {
                        change[side] = Json.mapper.writeValueAsString(elements)
                        changed = true
                    }
                }
            }

            if (changed) {
                versions.replaceOne(org.bson.Document("_id", doc.getObjectId("_id")), doc)
                migratedVersions++
            }
        }

        if (migratedVersions > 0 || unresolvedElements > 0) {
            AppLogger.success("Migration completed: Added type ids in $migratedVersions change log versions, $unresolvedElements elements left unresolved")
        } else {
            AppLogger.success("Migration check: All map change log snapshots already have type ids")
        }
    }

    /**
     * Converts stored map attribute values to the enum / price / distance definitions:
     * - camping `campingOfficial` (bool) -> `campingKind` (OFFICIAL_SITE / WILD_CAMPING)
     * - swimming / climbingspot / volleyball indoor-outdoor bools -> `*Setting` (INDOOR / OUTDOOR)
     * - offroad `motocross` + `enduro` bools -> `offroadMotorcycleDiscipline` (MOTOCROSS / ENDURO / BOTH)
     * - bicycle free-text underground type -> [BicycleUndergroundType] name (unmatched -> OTHER)
     * - swimming / climbingspot price and camping water distance int -> double
     *
     * Runs over `map_entries` and over the `locationData` JSON snapshots in `map_entry_versions`, so
     * reverting a pre-migration change can't bring the old shape back. Must run after
     * [migrateMapAttributeKeys] and before any typed [MapEntry] read. Migrated entries get `updatedAt`
     * bumped by 1ms so clients re-sync them without visibly changing the edit time. Idempotent - an
     * element is only touched while it still has an old field or old value type.
     */
    fun migrateMapAttributeTypes() {
        AppLogger.info("Running map attribute type migration...")

        val entries = mongoTemplate.getCollection("map_entries")
        var migratedEntries = 0

        entries.find().forEach { doc ->
            val locationDataList = doc.getList("locationData", org.bson.Document::class.java) ?: return@forEach
            var changed = false
            locationDataList.forEach { element ->
                if (migrateLocationDataElementTypes(element)) changed = true
            }

            if (changed) {
                val id = doc.getObjectId("_id")
                entries.replaceOne(org.bson.Document("_id", id), doc)
                // Bump through the typed mapping so updatedAt keeps the BSON shape Spring Data uses for Instant
                mongoTemplate.findById(id, MapEntry::class.java)?.let { entry ->
                    mongoTemplate.save(entry.copy(updatedAt = entry.updatedAt + 1.milliseconds))
                }
                migratedEntries++
            }
        }

        val versions = mongoTemplate.getCollection("map_entry_versions")
        var migratedVersions = 0

        versions.find(org.bson.Document("changes.field", "locationData")).forEach { doc ->
            val changes = doc.getList("changes", org.bson.Document::class.java) ?: return@forEach
            var changed = false

            changes.filter { it.getString("field") == "locationData" }.forEach { change ->
                for (side in listOf("oldValue", "newValue")) {
                    val rawJson = change.getString(side) ?: continue
                    val elements = try {
                        Json.mapper.readValue(rawJson, object : TypeReference<List<MutableMap<String, Any?>>>() {})
                    } catch (e: Exception) {
                        AppLogger.warn("Map attribute type migration: skipping unreadable snapshot in version ${doc.getObjectId("_id")}: ${e.message}", tag = "migrate_map_snapshot_skipped")
                        continue
                    }

                    var sideChanged = false
                    elements.forEach { element ->
                        if (migrateLocationDataElementTypes(element)) sideChanged = true
                    }
                    if (sideChanged) {
                        change[side] = Json.mapper.writeValueAsString(elements)
                        changed = true
                    }
                }
            }

            if (changed) {
                versions.replaceOne(org.bson.Document("_id", doc.getObjectId("_id")), doc)
                migratedVersions++
            }
        }

        if (migratedEntries > 0 || migratedVersions > 0) {
            AppLogger.success("Migration completed: Converted map attribute types in $migratedEntries map entries and $migratedVersions change log versions")
        } else {
            AppLogger.success("Migration check: All map attribute types already migrated")
        }
    }

    /**
     * Migrates one raw `locationData[]` element in place (a BSON document from `map_entries` or a
     * Jackson map from a change log snapshot - both are plain mutable maps with `{_class, value}`
     * attribute values). Returns true if anything changed. See [migrateMapAttributeTypes].
     */
    private fun migrateLocationDataElementTypes(element: MutableMap<String, Any?>): Boolean {
        var changed = false

        fun attributeValue(type: String, value: Any) = org.bson.Document("_class", type).append("value", value)
        fun attributeOf(field: String) = element[field] as? Map<*, *>
        fun boolOf(field: String) = attributeOf(field)?.get("value") as? Boolean

        fun intToDouble(field: String) {
            val attribute = attributeOf(field) ?: return
            if (attribute["_class"] != "int") return
            val number = attribute["value"] as? Number ?: return
            element[field] = attributeValue("double", number.toDouble())
            changed = true
        }

        fun boolToEnum(oldField: String, newField: String, ifTrue: String, ifFalse: String, ifMissing: String? = null) {
            if (!element.containsKey(oldField)) return
            val enumName = when (boolOf(oldField)) {
                true -> ifTrue
                false -> ifFalse
                null -> ifMissing
            }
            element.remove(oldField)
            if (element[newField] == null && enumName != null) {
                element[newField] = attributeValue("string", enumName)
            }
            changed = true
        }

        when (element["_class"]) {
            "camping" -> {
                boolToEnum("campingOfficial", "campingKind", ifTrue = "OFFICIAL_SITE", ifFalse = "WILD_CAMPING", ifMissing = "OFFICIAL_SITE")
                intToDouble("campingWaterDistance")
            }
            "swimming" -> {
                boolToEnum("swimmingIndoor", "swimmingSetting", ifTrue = "INDOOR", ifFalse = "OUTDOOR")
                intToDouble("swimmingPrice")
            }
            "climbingspot" -> {
                boolToEnum("climbingspotOutdoor", "climbingspotSetting", ifTrue = "OUTDOOR", ifFalse = "INDOOR")
                intToDouble("climbingspotPrice")
            }
            "volleyball" -> {
                boolToEnum("volleyballOutdoor", "volleyballSetting", ifTrue = "OUTDOOR", ifFalse = "INDOOR")
            }
            "offroad_motorcycle" -> {
                if (element.containsKey("offroadMotorcycleMotocross") || element.containsKey("offroadMotorcycleEnduro")) {
                    val motocross = boolOf("offroadMotorcycleMotocross") == true
                    val enduro = boolOf("offroadMotorcycleEnduro") == true
                    element.remove("offroadMotorcycleMotocross")
                    element.remove("offroadMotorcycleEnduro")
                    val discipline = when {
                        motocross && enduro -> "BOTH"
                        motocross -> "MOTOCROSS"
                        enduro -> "ENDURO"
                        else -> null
                    }
                    if (element["offroadMotorcycleDiscipline"] == null && discipline != null) {
                        element["offroadMotorcycleDiscipline"] = attributeValue("string", discipline)
                    }
                    changed = true
                }
            }
            "bicycle" -> {
                val text = attributeOf("bicycleUndergroundType")?.get("value") as? String
                if (text != null && BicycleUndergroundType.entries.none { it.name == text }) {
                    element["bicycleUndergroundType"] = if (text.isBlank()) {
                        null
                    } else {
                        val normalized = text.lowercase()
                        val type = when {
                            "asphalt" in normalized || "teer" in normalized -> BicycleUndergroundType.ASPHALT
                            "schotter" in normalized || "kies" in normalized || "gravel" in normalized -> BicycleUndergroundType.GRAVEL
                            "dirt" in normalized || "erde" in normalized -> BicycleUndergroundType.DIRT
                            "trail" in normalized -> BicycleUndergroundType.TRAIL
                            else -> BicycleUndergroundType.OTHER
                        }
                        attributeValue("string", type.name)
                    }
                    changed = true
                }
            }
        }

        return changed
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