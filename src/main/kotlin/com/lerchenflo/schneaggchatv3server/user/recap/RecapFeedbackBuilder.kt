@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.user.recap

import com.lerchenflo.schneaggchatv3server.feedback.model.FeedbackEntry
import com.lerchenflo.schneaggchatv3server.feedback.model.FeedbackStatus
import com.lerchenflo.schneaggchatv3server.feedback.model.FeedbackType
import com.lerchenflo.schneaggchatv3server.feedback.model.FeedbackVote
import com.lerchenflo.schneaggchatv3server.repository.FeedbackCommentRepository
import com.lerchenflo.schneaggchatv3server.repository.FeedbackEntryRepository
import com.lerchenflo.schneaggchatv3server.user.recap.model.FeedbackEntryRecap
import com.lerchenflo.schneaggchatv3server.user.recap.model.FeedbackRecap
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import kotlin.time.ExperimentalTime

/** The feedback board part of the recap: own entries, votes, comments and dev replies. */
@Component
class RecapFeedbackBuilder(
    private val feedbackEntryRepository: FeedbackEntryRepository,
    private val feedbackCommentRepository: FeedbackCommentRepository,
) {

    // NONE is never stored, so it is not a key of the vote maps.
    private val voteKeys = FeedbackVote.entries.filter { it != FeedbackVote.NONE }

    fun build(requesterId: ObjectId, yearStart: Long, yearEnd: Long): FeedbackRecap {
        val requesterKey = requesterId.toHexString()
        // The board is small, so one read of all visible entries is cheaper than several queries.
        val entries = feedbackEntryRepository.findByDeletedFalse()
        val mine = entries.filter { it.creatorId == requesterId }
        fun inYear(epochSeconds: Long) = epochSeconds in yearStart until yearEnd

        val bugs = mine.filter { it.type == FeedbackType.BUG }
        val features = mine.filter { it.type == FeedbackType.FEATURE }

        val votesCast = voteKeys.associate { vote ->
            vote.name to entries.count { it.votes[requesterKey] == vote }.toLong()
        }
        val votesReceived = voteKeys.associate { vote ->
            vote.name to mine.sumOf { entry -> entry.votes.count { (voter, v) -> voter != requesterKey && v == vote } }.toLong()
        }

        fun FeedbackEntry.upVotesFromOthers() =
            votes.count { (voter, vote) -> voter != requesterKey && vote == FeedbackVote.UP }.toLong()

        val topEntry = mine
            .filter { it.upVotesFromOthers() > 0 }
            .sortedWith(compareByDescending<FeedbackEntry> { it.upVotesFromOthers() }.thenByDescending { it.createdAt })
            .firstOrNull()
            ?.let { entry ->
                FeedbackEntryRecap(
                    id = entry.id.toHexString(),
                    type = entry.type.name,
                    title = entry.title,
                    status = entry.status.name,
                    upVotes = entry.upVotesFromOthers(),
                )
            }

        val comments = feedbackCommentRepository.findByAuthorId(requesterId)
        val devReplies = if (mine.isEmpty()) emptyList() else
            feedbackCommentRepository.findByEntryIdInAndDevCommentTrue(mine.map { it.id })
                .filter { it.authorId != requesterId }

        return FeedbackRecap(
            bugsReportedThisYear = bugs.count { inYear(it.createdAt.epochSeconds) }.toLong(),
            bugsReportedAllTime = bugs.size.toLong(),
            featuresRequestedThisYear = features.count { inYear(it.createdAt.epochSeconds) }.toLong(),
            featuresRequestedAllTime = features.size.toLong(),
            myEntriesByStatus = FeedbackStatus.entries.associate { status -> status.name to mine.count { it.status == status }.toLong() },
            votesCast = votesCast,
            votesReceived = votesReceived,
            topEntry = topEntry,
            commentsWrittenThisYear = comments.count { inYear(it.createdAt.epochSeconds) }.toLong(),
            commentsWrittenAllTime = comments.size.toLong(),
            devRepliesReceivedThisYear = devReplies.count { inYear(it.createdAt.epochSeconds) }.toLong(),
            devRepliesReceivedAllTime = devReplies.size.toLong(),
        )
    }
}
