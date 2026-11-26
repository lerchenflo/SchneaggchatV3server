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
    // Always empty: per-board results were replaced by gamesLeaderboard. Kept so older clients,
    // which require the field, still deserialize the response.
    val games: List<Any> = emptyList(),
    val gamesLeaderboard: GamesLeaderboardRecap,
    val betaTester: BetaTesterRecap,
    val passwordResets: PasswordResetRecap,
    val usageTime: UsageTimeRecap,
    val feedback: FeedbackRecap,
    val chatHabits: ChatHabitsRecap,
    val wakes: WakeRecap,
)

data class TextCount(val text: String, val count: Long)

// A user or group with a count; `name` is resolved server-side like everywhere else in the recap.
data class NamedCount(val id: String, val name: String, val group: Boolean, val count: Long)

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
    val leaderboard: MapLeaderboardRecap,
)

data class MapLeaderboardRow(
    val rank: Int,
    val userId: String,
    val username: String,
    val contributionCount: Long,
)

// Global, app-wide ranking by map entries created + edited in the recap's year. Same top-N +
// own-true-rank shape as LeaderboardRecap so the client can render both with one component.
data class MapLeaderboardRecap(
    val top: List<MapLeaderboardRow>,
    val myRank: Int?,
    val myContributionCount: Long,
)

data class GamesLeaderboardRow(
    val rank: Int,
    val userId: String,
    val username: String,
    val points: Long,
    val boardsPlayed: Int,
    val gamesPlayed: Int,
)

// The public global games ranking (all games together, percentile points per board) - the same
// list the games screen shows. Same top-N + own-true-rank shape as LeaderboardRecap.
data class GamesLeaderboardRecap(
    val top: List<GamesLeaderboardRow>,
    val myRank: Int?,
    val myPoints: Long,
    val myBoardsPlayed: Int,
    val myGamesPlayed: Int,
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
    // Details of the requester's own exceptions, all time like the ranking. Rows logged before
    // 2026-09-04 carry no exception class / path, so these can be null even when the count is > 0.
    val myTopException: TextCount?,       // exception class name, e.g. "IllegalStateException"
    val myTopEndpoint: TextCount?,        // request path, ObjectIds replaced by "{id}"
    val myFirstExceptionAt: Long?,
    // Server-side push deliveries to the requester that failed (FCM/APNs). Not bugs the user hit,
    // so they are not part of myExceptionCount or the ranking.
    val myPushFailuresThisYear: Long,
    val myPushFailuresAllTime: Long,
)

data class PasswordResetRecap(
    val passwordResetEmailsSentThisYear: Long,
    val passwordResetEmailsSentAllTime: Long,
    // Logins with this account's username and a wrong password. Usually the user mistyping, but
    // anyone who knows the username can cause one.
    val failedLoginsThisYear: Long,
    val failedLoginsAllTime: Long,
)

data class DurationDayCount(val date: String, val millis: Long)
data class DurationMonthCount(val month: Int, val millis: Long)

// Time connected over the WebSocket, from the `socketconnections` log. Every session counts on its
// own: two devices online at the same time add up (intentionally not merged). Sessions are
// attributed to the day/month/hour they started in.
data class UsageTimeRecap(
    val totalMillisThisYear: Long,
    val totalMillisAllTime: Long,
    val sessionCountThisYear: Long,
    val averageSessionMillis: Long,
    val longestSessionMillis: Long,
    val longestSessionAt: Long?,
    val busiestDay: DurationDayCount?,
    val busiestHourOfDay: Int?,
    val perMonth: List<DurationMonthCount>,
    // Earliest session in the whole collection - connection logging only started mid-2026, so the
    // client can tell the user that earlier usage is missing.
    val trackingSince: Long?,
)

data class FeedbackEntryRecap(
    val id: String,
    val type: String,        // FeedbackType: FEATURE or BUG
    val title: String,
    val status: String,      // FeedbackStatus
    val upVotes: Long,
)

// The requester on the in-app feedback board. Entries and comments are split by year via their
// createdAt. Votes carry no date, so vote counts are the current votes, all time.
data class FeedbackRecap(
    val bugsReportedThisYear: Long,
    val bugsReportedAllTime: Long,
    val featuresRequestedThisYear: Long,
    val featuresRequestedAllTime: Long,
    // Own entries (all time) by current status. Always holds every FeedbackStatus name, 0 when none:
    // REQUESTED, PLANNED, IMPLEMENTED (features), OPEN, CONFIRMED, FIXED (bugs).
    val myEntriesByStatus: Map<String, Long>,
    // Votes the requester cast on any entry, by FeedbackVote name: UP, DOWN, DIDNT_KNOW, NOT_NEEDED (always all four).
    val votesCast: Map<String, Long>,
    // Votes other users cast on the requester's entries, same keys.
    val votesReceived: Map<String, Long>,
    // Own entry with the most UP votes from others (ties: newest), null without own entries with an upvote.
    val topEntry: FeedbackEntryRecap?,
    val commentsWrittenThisYear: Long,
    val commentsWrittenAllTime: Long,
    // Comments by an admin (devComment) on the requester's own entries, not counting the requester's own.
    val devRepliesReceivedThisYear: Long,
    val devRepliesReceivedAllTime: Long,
)

data class ConversationStarterRow(
    val id: String,
    val name: String,
    val conversations: Long,
    val startedByMe: Long,
)

data class MonologueRecap(
    val messageCount: Int,
    val partnerId: String,
    val partnerName: String,
    val startedAt: Long,
)

data class ReaderSpeed(val id: String, val name: String, val medianMillis: Long, val sampleCount: Long)

// Direct chats only (no groups, no system messages), recap year only. A conversation starts with
// the first message of a chat or with a message sent 6 h or more after the previous one.
data class ChatHabitsRecap(
    // Time from a message sent to the requester until they read it (read receipt).
    val medianReadMillis: Long?,
    val readSampleCount: Long,
    // Partner who reads the requester's messages fastest (median, at least 10 read messages).
    val fastestReader: ReaderSpeed?,
    // Time from the first unanswered message of a partner until the requester's next message.
    val medianReplyMillis: Long?,
    val replySampleCount: Long,
    val conversations: Long,
    val conversationsStartedByMe: Long,
    // Up to 5 partners with the most conversations, most first.
    val starterPerPartner: List<ConversationStarterRow>,
    // Most used first message (text messages, lower case, trailing punctuation removed, at most 40
    // characters) of the conversations the requester started. Null when none was used twice.
    val topOpener: TextCount?,
    // Longest run of the requester's messages in one chat without a message from the partner in between.
    val longestMonologue: MonologueRecap?,
    // Emoji typed in message text (not reactions), all chats. Most used first, at most 5.
    val topEmojiTyped: List<EmojiCount>,
)

// Wakes (alarm ringtone). Sent: from the server log. Received: direct wakes to the requester.
data class WakeRecap(
    val wakesSentThisYear: Long,
    val wakesSentAllTime: Long,
    // User or group the requester woke most this year.
    val mostWoken: NamedCount?,
    // Most used wake reason this year (case-insensitive, blank reasons skipped).
    val topReason: TextCount?,
    val wakesReceivedThisYear: Long,
    val wakesReceivedAllTime: Long,
    // User who woke the requester most this year (direct wakes only).
    val topWaker: NamedCount?,
    // Wakes others sent to groups the requester is in, this year. Whether the requester's phone
    // actually rang depends on their own wake settings, so this is "group wakes", not "woken".
    val groupWakesThisYear: Long,
)
