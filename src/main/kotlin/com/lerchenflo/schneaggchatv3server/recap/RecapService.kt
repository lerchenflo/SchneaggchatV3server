@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.recap

import com.lerchenflo.schneaggchatv3server.games.GamesService
import com.lerchenflo.schneaggchatv3server.games.model.Difficulty
import com.lerchenflo.schneaggchatv3server.games.model.Game
import com.lerchenflo.schneaggchatv3server.games.model.LeaderboardPeriod
import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.message.MessageLookupService
import com.lerchenflo.schneaggchatv3server.message.messagemodel.Message
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageType
import com.lerchenflo.schneaggchatv3server.recap.model.AccountRecap
import com.lerchenflo.schneaggchatv3server.recap.model.DayCount
import com.lerchenflo.schneaggchatv3server.recap.model.EmojiCount
import com.lerchenflo.schneaggchatv3server.recap.model.GameRecapEntry
import com.lerchenflo.schneaggchatv3server.recap.model.GroupActivity
import com.lerchenflo.schneaggchatv3server.recap.model.GroupsRecap
import com.lerchenflo.schneaggchatv3server.recap.model.LeaderboardRecap
import com.lerchenflo.schneaggchatv3server.recap.model.LeaderboardRow
import com.lerchenflo.schneaggchatv3server.recap.model.LongestMessageRecap
import com.lerchenflo.schneaggchatv3server.recap.model.MapRecap
import com.lerchenflo.schneaggchatv3server.recap.model.MessagingRecap
import com.lerchenflo.schneaggchatv3server.recap.model.MonthCount
import com.lerchenflo.schneaggchatv3server.recap.model.MostReactedMessage
import com.lerchenflo.schneaggchatv3server.recap.model.PartnerRecap
import com.lerchenflo.schneaggchatv3server.recap.model.PollsRecap
import com.lerchenflo.schneaggchatv3server.recap.model.ReactionsRecap
import com.lerchenflo.schneaggchatv3server.recap.model.RecapResponse
import com.lerchenflo.schneaggchatv3server.recap.model.SocialRecap
import com.lerchenflo.schneaggchatv3server.repository.GroupRepository
import com.lerchenflo.schneaggchatv3server.repository.LogRepository
import com.lerchenflo.schneaggchatv3server.repository.MapEntryVersionRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapChangeType
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipStatus
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import com.lerchenflo.schneaggchatv3server.util.LogType
import org.bson.types.ObjectId
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val LEADERBOARD_SIZE = 20
private val RECAP_ZONE: ZoneId = ZoneId.of("Europe/Vienna")

@Service
class RecapService(
    private val messageLookupService: MessageLookupService,
    private val groupLookupService: GroupLookupService,
    private val groupRepository: GroupRepository,
    private val userLookupService: UserLookupService,
    private val friendsLookupService: FriendsLookupService,
    private val gamesService: GamesService,
    private val logRepository: LogRepository,
    private val mapEntryVersionRepository: MapEntryVersionRepository,
    private val mongoTemplate: MongoTemplate,
) {

    fun buildRecap(requesterId: ObjectId, year: Int): RecapResponse {
        val yearStart = ZonedDateTime.of(year, 1, 1, 0, 0, 0, 0, RECAP_ZONE).toEpochSecond()
        val yearEnd = ZonedDateTime.of(year + 1, 1, 1, 0, 0, 0, 0, RECAP_ZONE).toEpochSecond()

        val self = requireNotNull(userLookupService.findById(requesterId)) { "Requesting user not found" }
        val allMessages = messageLookupService.getAllUserMessages(requesterId)
        val yearMessages = allMessages.filter { it.sendDate.epochSeconds in yearStart until yearEnd }

        // Collect every user/group id referenced anywhere so we can resolve names in one batch call each -
        // the client may not have these users/groups synced, so every id below ships with a resolved name.
        val userIds = mutableSetOf(requesterId)
        val groupIds = mutableSetOf<ObjectId>()
        allMessages.forEach { message ->
            if (message.groupMessage) {
                groupIds += message.receiverId
            } else {
                userIds += message.senderId
                userIds += message.receiverId
            }
            message.reactions.forEach { userIds += it.userId }
            message.poll?.voteOptions?.forEach { option -> option.voters.forEach { userIds += it.userId } }
        }

        val usernames = userLookupService.findAllById(userIds.toList()).associate { it.id to it.username }
        val groupNames = groupRepository.findAllById(groupIds.toList()).associate { it.id to it.name }
        val nameFor: (ObjectId) -> String = { usernames[it] ?: "Unknown" }
        val groupNameFor: (ObjectId) -> String = { groupNames[it] ?: "Unknown group" }

        return RecapResponse(
            year = year,
            generatedAt = Clock.System.now().toEpochMilliseconds(),
            account = buildAccountRecap(self, requesterId, yearStart, yearEnd),
            messaging = buildMessagingRecap(allMessages, yearMessages, requesterId, nameFor, groupNameFor),
            reactions = buildReactionsRecap(allMessages, yearMessages, requesterId),
            polls = buildPollsRecap(allMessages, requesterId),
            social = buildSocialRecap(requesterId, yearStart, yearEnd),
            topPartners = buildTopPartners(yearMessages, requesterId, nameFor, groupNameFor),
            globalLeaderboard = buildLeaderboard(requesterId, yearStart, yearEnd),
            groups = buildGroupsRecap(requesterId, yearMessages, groupNameFor),
            map = buildMapRecap(requesterId, yearStart, yearEnd),
            games = buildGamesRecap(requesterId),
        )
    }

    private fun buildAccountRecap(self: User, requesterId: ObjectId, yearStart: Long, yearEnd: Long): AccountRecap {
        val logins = logRepository.findByLogTypeAndUserId(LogType.USER_LOGIN, requesterId)
        val friendRequests = logRepository.findByLogTypeAndUserId(LogType.FRIEND_REQUEST_SENT, requesterId)
        val loginsThisYear = logins.count { it.timestamp.epochSeconds in yearStart until yearEnd }
        val friendRequestsThisYear = friendRequests.count { it.timestamp.epochSeconds in yearStart until yearEnd }

        return AccountRecap(
            userId = requesterId.toHexString(),
            username = self.username,
            memberSince = self.createdAt.toEpochMilliseconds(),
            accountAgeDays = (Clock.System.now() - self.createdAt).inWholeDays.toInt(),
            emailVerified = self.emailVerifiedAt != null,
            loginCountThisYear = loginsThisYear.toLong(),
            loginCountAllTime = logins.size.toLong(),
            friendRequestsSentThisYear = friendRequestsThisYear.toLong(),
            friendRequestsSentAllTime = friendRequests.size.toLong(),
        )
    }

    // kotlin.time.Instant is stored as a nested {epochSeconds, nanosecondsOfSecond} doc rather than a
    // native date - rebuild a zoned java.time value locally for calendar/hour bucketing.
    private fun Message.sentZoned() =
        java.time.Instant.ofEpochSecond(sendDate.epochSeconds, sendDate.nanosecondsOfSecond.toLong()).atZone(RECAP_ZONE)

    private fun buildMessagingRecap(
        allMessages: List<Message>,
        yearMessages: List<Message>,
        requesterId: ObjectId,
        nameFor: (ObjectId) -> String,
        groupNameFor: (ObjectId) -> String,
    ): MessagingRecap {
        val sentAllTimeMessages = allMessages.filter { it.senderId == requesterId }
        val sentYearMessages = yearMessages.filter { it.senderId == requesterId }

        val sentByType = sentYearMessages.groupingBy { it.msgType.name }.eachCount()
            .mapValues { it.value.toLong() }

        val textLike = sentYearMessages.filter {
            (it.msgType == MessageType.TEXT || it.msgType == MessageType.IMAGE) && it.content.isNotBlank()
        }
        val totalChars = textLike.sumOf { it.content.length.toLong() }
        val totalWords = textLike.sumOf { it.content.trim().split(Regex("\\s+")).size.toLong() }
        val avgLength = if (textLike.isNotEmpty()) textLike.map { it.content.length }.average() else 0.0
        val longest = textLike.maxByOrNull { it.content.length }?.let {
            LongestMessageRecap(
                content = it.content,
                length = it.content.length,
                sentAt = it.sendDate.toEpochMilliseconds(),
                toId = it.receiverId.toHexString(),
                toName = if (it.groupMessage) groupNameFor(it.receiverId) else nameFor(it.receiverId),
                group = it.groupMessage,
            )
        }

        val dayCounts = sentYearMessages.groupingBy { it.sentZoned().toLocalDate() }.eachCount()
        val busiestDay = dayCounts.entries.maxByOrNull { it.value }
            ?.let { DayCount(date = it.key.toString(), count = it.value.toLong()) }

        val hourCounts = sentYearMessages.groupingBy { it.sentZoned().hour }.eachCount()
        val busiestHour = hourCounts.entries.maxByOrNull { it.value }?.key

        val perMonth = (1..12).map { month ->
            MonthCount(month = month, count = sentYearMessages.count { it.sentZoned().monthValue == month }.toLong())
        }
        val mostActiveMonth = perMonth.filter { it.count > 0 }.maxByOrNull { it.count }

        val sentDates = sentAllTimeMessages.map { it.sentZoned().toLocalDate() }.toSortedSet()
        var longestStreak = 0
        var currentStreak = 0
        var previous: LocalDate? = null
        for (date in sentDates) {
            currentStreak = if (previous != null && date == previous.plusDays(1)) currentStreak + 1 else 1
            longestStreak = maxOf(longestStreak, currentStreak)
            previous = date
        }

        val firstMessageEverAt = sentAllTimeMessages.minByOrNull { it.sendDate.epochSeconds }?.sendDate?.toEpochMilliseconds()

        return MessagingRecap(
            messagesSentThisYear = sentYearMessages.size.toLong(),
            messagesReceivedThisYear = (yearMessages.size - sentYearMessages.size).toLong(),
            messagesSentAllTime = sentAllTimeMessages.size.toLong(),
            messagesReceivedAllTime = (allMessages.size - sentAllTimeMessages.size).toLong(),
            sentByType = sentByType,
            totalCharactersTypedThisYear = totalChars,
            totalWordsTypedThisYear = totalWords,
            averageMessageLength = avgLength,
            longestMessage = longest,
            busiestDay = busiestDay,
            busiestHourOfDay = busiestHour,
            mostActiveMonth = mostActiveMonth,
            perMonth = perMonth,
            longestStreakDays = longestStreak,
            firstMessageEverAt = firstMessageEverAt,
        )
    }

    private fun buildReactionsRecap(
        allMessages: List<Message>,
        yearMessages: List<Message>,
        requesterId: ObjectId,
    ): ReactionsRecap {
        val givenAllTime = allMessages.sumOf { m -> m.reactions.count { it.userId == requesterId }.toLong() }
        val givenYear = yearMessages.sumOf { m -> m.reactions.count { it.userId == requesterId }.toLong() }
        val receivedAllTime = allMessages.filter { it.senderId == requesterId }.sumOf { it.reactions.size.toLong() }
        val receivedYear = yearMessages.filter { it.senderId == requesterId }.sumOf { it.reactions.size.toLong() }

        val topGiven = yearMessages.flatMap { m -> m.reactions.filter { it.userId == requesterId } }
            .groupingBy { it.content }.eachCount()
            .entries.sortedByDescending { it.value }.take(5)
            .map { EmojiCount(it.key, it.value.toLong()) }

        val receivedYearReactions = yearMessages.filter { it.senderId == requesterId }.flatMap { it.reactions }
        val topReceived = receivedYearReactions.groupingBy { it.content }.eachCount()
            .entries.sortedByDescending { it.value }.take(5)
            .map { EmojiCount(it.key, it.value.toLong()) }

        val mostReacted = yearMessages.filter { it.senderId == requesterId && it.reactions.isNotEmpty() }
            .maxByOrNull { it.reactions.size }
            ?.let { MostReactedMessage(content = it.content, reactionCount = it.reactions.size, sentAt = it.sendDate.toEpochMilliseconds()) }

        return ReactionsRecap(
            reactionsGivenThisYear = givenYear,
            reactionsReceivedThisYear = receivedYear,
            reactionsGivenAllTime = givenAllTime,
            reactionsReceivedAllTime = receivedAllTime,
            topEmojiGiven = topGiven,
            topEmojiReceived = topReceived,
            mostReactedMessage = mostReacted,
        )
    }

    private fun buildPollsRecap(allMessages: List<Message>, requesterId: ObjectId): PollsRecap {
        val pollsCreated = allMessages.count { it.msgType == MessageType.POLL && it.senderId == requesterId }
        val votesCast = allMessages.sumOf { (it.poll?.getVoteCountForUser(requesterId) ?: 0).toLong() }
        return PollsRecap(pollsCreated = pollsCreated.toLong(), pollVotesCast = votesCast)
    }

    private fun buildSocialRecap(requesterId: ObjectId, yearStart: Long, yearEnd: Long): SocialRecap {
        val friends = friendsLookupService.getFriends(requesterId)
        val pendingReceived = friendsLookupService.getPendingRequests(requesterId)
        val pendingSent = friendsLookupService.getSentRequests(requesterId)
        val newFriendsThisYear = friendsLookupService.getAllInteractions(requesterId).count {
            it.status == FriendshipStatus.ACCEPTED && (it.lastChanged?.epochSeconds ?: Long.MIN_VALUE) in yearStart until yearEnd
        }

        return SocialRecap(
            friendsCount = friends.size,
            pendingRequestsReceived = pendingReceived.size,
            pendingRequestsSent = pendingSent.size,
            newFriendsThisYear = newFriendsThisYear,
        )
    }

    private fun buildTopPartners(
        yearMessages: List<Message>,
        requesterId: ObjectId,
        nameFor: (ObjectId) -> String,
        groupNameFor: (ObjectId) -> String,
    ): List<PartnerRecap> {
        data class Key(val id: ObjectId, val isGroup: Boolean)
        data class Counts(var total: Long = 0, var fromMe: Long = 0, var fromThem: Long = 0)

        val counts = mutableMapOf<Key, Counts>()
        for (message in yearMessages) {
            val key = if (message.groupMessage) {
                Key(message.receiverId, true)
            } else {
                Key(if (message.senderId == requesterId) message.receiverId else message.senderId, false)
            }
            val bucket = counts.getOrPut(key) { Counts() }
            bucket.total++
            if (message.senderId == requesterId) bucket.fromMe++ else bucket.fromThem++
        }

        return counts.entries.sortedByDescending { it.value.total }.take(10).map { (key, bucket) ->
            PartnerRecap(
                id = key.id.toHexString(),
                name = if (key.isGroup) groupNameFor(key.id) else nameFor(key.id),
                group = key.isGroup,
                messagesExchanged = bucket.total,
                messagesFromMe = bucket.fromMe,
                messagesFromThem = bucket.fromThem,
            )
        }
    }

    private data class SenderCount(val senderId: ObjectId, val count: Long)

    // Global, app-wide "top chatters" leaderboard for the recap's year, mirroring the top-N + own-true-rank
    // pattern used by GamesService.getHighscores.
    private fun buildLeaderboard(requesterId: ObjectId, yearStart: Long, yearEnd: Long): LeaderboardRecap {
        val aggregation = Aggregation.newAggregation(
            Aggregation.match(
                Criteria.where("deleted").`is`(false)
                    .and("sendDate.epochSeconds").gte(yearStart).lt(yearEnd)
            ),
            Aggregation.group("senderId")
                .count().`as`("count")
                .first("senderId").`as`("senderId"),
            Aggregation.sort(Sort.Direction.DESC, "count"),
        )
        val ranked = mongoTemplate.aggregate(aggregation, "messages", SenderCount::class.java).mappedResults

        val top = ranked.take(LEADERBOARD_SIZE)
        val usernames = userLookupService.findAllById(top.map { it.senderId }).associate { it.id to it.username }
        val topRows = top.mapIndexed { index, entry ->
            LeaderboardRow(
                rank = index + 1,
                userId = entry.senderId.toHexString(),
                username = usernames[entry.senderId] ?: "Unknown",
                messageCount = entry.count,
            )
        }

        val myIndex = ranked.indexOfFirst { it.senderId == requesterId }
        return LeaderboardRecap(
            top = topRows,
            myRank = if (myIndex >= 0) myIndex + 1 else null,
            myMessageCount = ranked.getOrNull(myIndex)?.count ?: 0,
        )
    }

    private fun buildGroupsRecap(
        requesterId: ObjectId,
        yearMessages: List<Message>,
        groupNameFor: (ObjectId) -> String,
    ): GroupsRecap {
        val memberGroupIds = groupLookupService.getUserGroupIds(requesterId)
        val createdCount = groupRepository.findAllById(memberGroupIds).count { it.creatorId == requesterId }

        val mostActiveGroup = yearMessages.filter { it.groupMessage }
            .groupingBy { it.receiverId }.eachCount()
            .entries.maxByOrNull { it.value }
            ?.let { GroupActivity(groupId = it.key.toHexString(), groupName = groupNameFor(it.key), messageCount = it.value.toLong()) }

        return GroupsRecap(
            memberOfCount = memberGroupIds.size,
            createdCount = createdCount,
            mostActiveGroup = mostActiveGroup,
        )
    }

    private fun buildMapRecap(requesterId: ObjectId, yearStart: Long, yearEnd: Long): MapRecap {
        val versions = mapEntryVersionRepository.findByEditedBy(requesterId)
        val created = versions.filter { it.changeType == MapChangeType.CREATE }
        val edited = versions.filter { it.changeType == MapChangeType.UPDATE }

        return MapRecap(
            entriesCreatedThisYear = created.count { it.editedAt.epochSeconds in yearStart until yearEnd }.toLong(),
            entriesCreatedAllTime = created.size.toLong(),
            entriesEditedThisYear = edited.count { it.editedAt.epochSeconds in yearStart until yearEnd }.toLong(),
            entriesEditedAllTime = edited.size.toLong(),
        )
    }

    // Iterates every game/difficulty pair, reusing GamesService.getHighscores (which already carries the
    // requester's true rank) - explicitly not optimized for cost per product decision.
    private fun buildGamesRecap(requesterId: ObjectId): List<GameRecapEntry> {
        val requesterHex = requesterId.toHexString()
        val entries = mutableListOf<GameRecapEntry>()
        for (game in Game.entries) {
            for (difficulty in Difficulty.entries) {
                val mine = gamesService.getHighscores(game, difficulty, LeaderboardPeriod.ALL_TIME, requesterId).entries
                    .find { it.userId == requesterHex } ?: continue
                entries += GameRecapEntry(
                    game = game.name,
                    difficulty = difficulty.name,
                    bestScore = mine.score,
                    bestTimeMillis = mine.timeMillis,
                    rank = mine.rank,
                    achievedAt = mine.achievedAt,
                )
            }
        }
        return entries
    }
}
