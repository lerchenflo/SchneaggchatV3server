@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.games

import com.lerchenflo.schneaggchatv3server.games.model.Difficulty
import com.lerchenflo.schneaggchatv3server.games.model.Game
import com.lerchenflo.schneaggchatv3server.games.model.GameScore
import com.lerchenflo.schneaggchatv3server.games.model.GlobalRankingEntryResponse
import com.lerchenflo.schneaggchatv3server.games.model.GlobalRankingResponse
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
import kotlin.math.roundToLong
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

    /** One user's aggregated standing across all (game, difficulty) boards. */
    data class GlobalRankingRow(
        val userId: ObjectId,
        val points: Double,
        val boardsPlayed: Int,
        val gamesPlayed: Int,
    )

    companion object {
        /**
         * Converts per-board rankings into comparable global points: on each board a user earns
         * the share of other players they beat (0-100), so the board winner always gets 100
         * regardless of the game's score scale. Boards must be best-first; result is best-first.
         */
        internal fun accumulateGlobalPoints(boards: List<Pair<Game, List<UserBestScore>>>): List<GlobalRankingRow> {
            val points = mutableMapOf<ObjectId, Double>()
            val boardCounts = mutableMapOf<ObjectId, Int>()
            val games = mutableMapOf<ObjectId, MutableSet<Game>>()

            for ((game, ranked) in boards) {
                ranked.forEachIndexed { index, best ->
                    val boardPoints = if (ranked.size == 1) {
                        100.0
                    } else {
                        (ranked.size - 1 - index) / (ranked.size - 1).toDouble() * 100
                    }
                    points.merge(best.userId, boardPoints, Double::plus)
                    boardCounts.merge(best.userId, 1, Int::plus)
                    games.getOrPut(best.userId) { mutableSetOf() }.add(game)
                }
            }

            return points.map { (userId, total) ->
                GlobalRankingRow(
                    userId = userId,
                    points = total,
                    boardsPlayed = boardCounts.getValue(userId),
                    gamesPlayed = games.getValue(userId).size,
                )
            }.sortedWith(
                compareByDescending<GlobalRankingRow> { it.points }
                    .thenByDescending { it.boardsPlayed }
                    .thenBy { it.userId }
            )
        }
    }

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

    /** One user's best result per (game, difficulty) board, best first. */
    private fun rankedBests(game: Game, difficulty: Difficulty, cutoffEpochSeconds: Long?): List<UserBestScore> {
        // kotlin.time.Instant is stored as a nested {epochSeconds, nanosecondsOfSecond} doc,
        // so the period cutoff has to match on the epochSeconds field.
        var criteria = Criteria.where("game").`is`(game.name).and("difficulty").`is`(difficulty.name)
        cutoffEpochSeconds?.let { criteria = criteria.and("createdAt.epochSeconds").gte(it) }

        val aggregation = Aggregation.newAggregation(
            Aggregation.match(criteria),
            Aggregation.sort(game.leaderboardSort()),
            Aggregation.group("userId")
                .first("userId").`as`("userId")
                .first("score").`as`("score")
                .first("timeMillis").`as`("timeMillis")
                .first("createdAt").`as`("achievedAt"),
        )
        return mongoTemplate.aggregate(aggregation, "gamescores", UserBestScore::class.java)
            .mappedResults
            .sortedWith(game.leaderboardComparator())
    }

    fun getHighscores(game: Game, difficulty: Difficulty, period: LeaderboardPeriod, requesterId: ObjectId): HighscoresResponse {
        val ranked = rankedBests(game, difficulty, period.startEpochSeconds())

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

    fun getGlobalRanking(period: LeaderboardPeriod, requesterId: ObjectId): GlobalRankingResponse {
        val cutoff = period.startEpochSeconds()
        val boards = Game.entries.flatMap { game ->
            Difficulty.entries.map { difficulty -> game to rankedBests(game, difficulty, cutoff) }
        }
        val ranked = accumulateGlobalPoints(boards)

        // Ranks are zero-based indexes into the full ranking, so the requester keeps their true
        // rank even when appended below the top entries.
        val rows = buildList {
            addAll(ranked.take(LEADERBOARD_SIZE).mapIndexed { index, row -> index to row })
            val requesterIndex = ranked.indexOfFirst { it.userId == requesterId }
            if (requesterIndex >= LEADERBOARD_SIZE) add(requesterIndex to ranked[requesterIndex])
        }

        val usernames = userLookupService.findAllById(rows.map { it.second.userId })
            .associate { it.id to it.username }

        return GlobalRankingResponse(
            period = period.name,
            entries = rows.map { (index, row) ->
                GlobalRankingEntryResponse(
                    rank = index + 1,
                    userId = row.userId.toHexString(),
                    username = usernames[row.userId] ?: "Unknown",
                    points = row.points.roundToLong(),
                    boardsPlayed = row.boardsPlayed,
                    gamesPlayed = row.gamesPlayed,
                )
            },
        )
    }
}
