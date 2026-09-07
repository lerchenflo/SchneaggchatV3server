package com.lerchenflo.schneaggchatv3server.user.recap.model

data class RecapResponse(
    val year: Int,
    val generatedAt: Long,
    val account: AccountRecap,
    val messaging: MessagingRecap,
    val reactions: ReactionsRecap,
    val polls: PollsRecap,
    val social: SocialRecap,
    val topPartners: List<PartnerRecap>,
    val globalLeaderboard: LeaderboardRecap,
    val groups: GroupsRecap,
    val map: MapRecap,
    val games: List<GameRecapEntry>,
    val betaTester: BetaTesterRecap,
    val passwordResets: PasswordResetRecap,
)

data class AccountRecap(
    val userId: String,
    val username: String,
    val memberSince: Long,
    val accountAgeDays: Int,
    val emailVerified: Boolean,
    val loginCountThisYear: Long,
    val loginCountAllTime: Long,
    val friendRequestsSentThisYear: Long,
    val friendRequestsSentAllTime: Long,
)

data class MessagingRecap(
    val messagesSentThisYear: Long,
    val messagesReceivedThisYear: Long,
    val messagesSentAllTime: Long,
    val messagesReceivedAllTime: Long,
    val sentByType: Map<String, Long>,
    val totalCharactersTypedThisYear: Long,
    val totalWordsTypedThisYear: Long,
    val averageMessageLength: Double,
    val longestMessage: LongestMessageRecap?,
    val busiestDay: DayCount?,
    val busiestHourOfDay: Int?,
    val mostActiveMonth: MonthCount?,
    val perMonth: List<MonthCount>,
    val longestStreakDays: Int,
    val firstMessageEverAt: Long?,
)

data class LongestMessageRecap(
    val content: String,
    val length: Int,
    val sentAt: Long,
    val toId: String,
    val toName: String,
    val group: Boolean,
)

data class DayCount(val date: String, val count: Long)
data class MonthCount(val month: Int, val count: Long)

data class ReactionsRecap(
    val reactionsGivenThisYear: Long,
    val reactionsReceivedThisYear: Long,
    val reactionsGivenAllTime: Long,
    val reactionsReceivedAllTime: Long,
    val topEmojiGiven: List<EmojiCount>,
    val topEmojiReceived: List<EmojiCount>,
    val mostReactedMessage: MostReactedMessage?,
)

data class EmojiCount(val emoji: String, val count: Long)
data class MostReactedMessage(val content: String, val reactionCount: Int, val sentAt: Long)

data class PollsRecap(
    val pollsCreated: Long,
    val pollVotesCast: Long,
)

data class SocialRecap(
    val friendsCount: Int,
    val pendingRequestsReceived: Int,
    val pendingRequestsSent: Int,
    val newFriendsThisYear: Int,
)

data class PartnerRecap(
    val id: String,
    val name: String,
    val group: Boolean,
    val messagesExchanged: Long,
    val messagesFromMe: Long,
    val messagesFromThem: Long,
)

data class LeaderboardRow(
    val rank: Int,
    val userId: String,
    val username: String,
    val messageCount: Long,
)

// Global, app-wide ranking by messages sent in the recap's year. myRank/myMessageCount reflect the
// requester's true position even when it falls outside the `top` list.
data class LeaderboardRecap(
    val top: List<LeaderboardRow>,
    val myRank: Int?,
    val myMessageCount: Long,
)

data class GroupActivity(
    val groupId: String,
    val groupName: String,
    val messageCount: Long,
)

data class GroupsRecap(
    val memberOfCount: Int,
    val createdCount: Int,
    val mostActiveGroup: GroupActivity?,
)

data class MapRecap(
    val entriesCreatedThisYear: Long,
    val entriesCreatedAllTime: Long,
    val entriesEditedThisYear: Long,
    val entriesEditedAllTime: Long,
)

data class GameRecapEntry(
    val game: String,
    val difficulty: String,
    val bestScore: Long,
    val bestTimeMillis: Long,
    val rank: Int,
    val achievedAt: Long,
)

data class BetaTesterRow(
    val rank: Int,
    val userId: String,
    val username: String,
    val exceptionCount: Long,
)

// All-time, not year-scoped - the population of users who ever hit an exception is small
// enough that the full list (not just a top-N) is shipped to the client.
data class BetaTesterRecap(
    val all: List<BetaTesterRow>,
    val myRank: Int?,
    val myExceptionCount: Long,
)

data class PasswordResetRecap(
    val passwordResetEmailsSentThisYear: Long,
    val passwordResetEmailsSentAllTime: Long,
)
