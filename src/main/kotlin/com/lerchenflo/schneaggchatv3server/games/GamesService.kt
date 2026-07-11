@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.games

import com.lerchenflo.schneaggchatv3server.games.model.Difficulty
import com.lerchenflo.schneaggchatv3server.games.model.Game
import com.lerchenflo.schneaggchatv3server.games.model.GameScore
import com.lerchenflo.schneaggchatv3server.games.model.HighscoreEntryResponse
import com.lerchenflo.schneaggchatv3server.games.model.HighscoresResponse
import com.lerchenflo.schneaggchatv3server.games.model.LeaderboardPeriod
import com.lerchenflo.schneaggchatv3server.repository.GameScoreRepository
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.stereotype.Service
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private const val LEADERBOARD_SIZE = 20

@Service
class GamesService(
    private val gameScoreRepository: GameScoreRepository,
    private val mongoTemplate: MongoTemplate,
    private val userLookupService: UserLookupService,
    private val loggingService: LoggingService,
) {

    /** One user's best result for a game, reduced from the full submission history. */
    data class UserBestScore(
        val userId: ObjectId,
        val score: Long,
        val timeMillis: Long,
        val achievedAt: Instant,
    )

    fun submitScore(game: Game, difficulty: Difficulty, score: Long, timeMillis: Long, requesterId: ObjectId): GameScore {
        val saved = gameScoreRepository.save(
            GameScore(
                userId = requesterId,
                game = game,
                difficulty = difficulty,
                score = score,
                timeMillis = timeMillis,
            )
        )
        loggingService.log(userId = requesterId, logType = LogType.GAME_SCORE_SUBMITTED, message = "${game.name} (${difficulty.name})")
        return saved
    }

    fun getHighscores(game: Game, difficulty: Difficulty, period: LeaderboardPeriod, requesterId: ObjectId): HighscoresResponse {
        // kotlin.time.Instant is stored as a nested {epochSeconds, nanosecondsOfSecond} doc,
        // so the period cutoff has to match on the epochSeconds field.
        var criteria = Criteria.where("game").`is`(game.name).and("difficulty").`is`(difficulty.name)
        period.startEpochSeconds()?.let { criteria = criteria.and("createdAt.epochSeconds").gte(it) }

        val aggregation = Aggregation.newAggregation(
            Aggregation.match(criteria),
            Aggregation.sort(game.leaderboardSort()),
            Aggregation.group("userId")
                .first("userId").`as`("userId")
                .first("score").`as`("score")
                .first("timeMillis").`as`("timeMillis")
                .first("createdAt").`as`("achievedAt"),
        )
        val ranked = mongoTemplate.aggregate(aggregation, "gamescores", UserBestScore::class.java)
            .mappedResults
            .sortedWith(game.leaderboardComparator())

        // Ranks are zero-based indexes into the full ranking, so the requester keeps their true
        // rank even when appended below the top entries.
        val rows = buildList {
            addAll(ranked.take(LEADERBOARD_SIZE).mapIndexed { index, best -> index to best })
            val requesterIndex = ranked.indexOfFirst { it.userId == requesterId }
            if (requesterIndex >= LEADERBOARD_SIZE) add(requesterIndex to ranked[requesterIndex])
        }

        val usernames = userLookupService.findAllById(rows.map { it.second.userId })
            .associate { it.id to it.username }

        return HighscoresResponse(
            gameId = game.name,
            difficulty = difficulty.name,
            period = period.name,
            entries = rows.map { (index, best) ->
                HighscoreEntryResponse(
                    rank = index + 1,
                    userId = best.userId.toHexString(),
                    username = usernames[best.userId] ?: "Unknown",
                    score = best.score,
                    timeMillis = best.timeMillis,
                    achievedAt = best.achievedAt.toEpochMilliseconds(),
                )
            },
        )
    }
}
