package com.lerchenflo.schneaggchatv3server.games

import com.lerchenflo.schneaggchatv3server.core.security.requireAuth
import com.lerchenflo.schneaggchatv3server.games.model.Difficulty
import com.lerchenflo.schneaggchatv3server.games.model.Game
import com.lerchenflo.schneaggchatv3server.games.model.GameScoreResponse
import com.lerchenflo.schneaggchatv3server.games.model.GlobalRankingResponse
import com.lerchenflo.schneaggchatv3server.games.model.HighscoresResponse
import com.lerchenflo.schneaggchatv3server.games.model.LeaderboardPeriod
import com.lerchenflo.schneaggchatv3server.games.model.toGameScoreResponse
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.*

private const val MAX_BATCH_SCORES = 20

@RestController
@RequestMapping("/games")
class GamesController(
    private val gamesService: GamesService,
) {

    data class SubmitScoreRequest(
        @field:NotBlank(message = "Game id must not be blank")
        val gameId: String,
        // Games without a difficulty setting can omit this
        val difficulty: String = Difficulty.MEDIUM.name,
        @field:Min(0, message = "Score must not be negative")
        val score: Long,
        @field:Min(0, message = "Time must not be negative")
        val timeMillis: Long,
    )

    @PostMapping("/upsert")
    fun submitScore(@Valid @RequestBody request: SubmitScoreRequest): GameScoreResponse {
        val requesterId = requireAuth()
        val game = requireNotNull(Game.fromId(request.gameId)) { "Unknown game id: ${request.gameId}" }
        val difficulty = requireNotNull(Difficulty.fromId(request.difficulty)) { "Unknown difficulty: ${request.difficulty}" }
        return gamesService.submitScore(
            game = game,
            difficulty = difficulty,
            score = request.score,
            timeMillis = request.timeMillis,
            requesterId = requesterId,
        ).toGameScoreResponse()
    }

    data class BatchScoreEntry(
        @field:NotBlank(message = "User id must not be blank")
        val userId: String,
        @field:Min(0, message = "Score must not be negative")
        val score: Long,
        @field:Min(0, message = "Time must not be negative")
        val timeMillis: Long = 0,
    )

    data class SubmitBatchScoreRequest(
        @field:NotBlank(message = "Game id must not be blank")
        val gameId: String,
        // Games without a difficulty setting can omit this
        val difficulty: String = Difficulty.MEDIUM.name,
        @field:Valid
        @field:Size(min = 1, max = MAX_BATCH_SCORES, message = "Between 1 and $MAX_BATCH_SCORES scores per request")
        val scores: List<BatchScoreEntry>,
    )

    /**
     * Results of a game several people played on one device, or of a friend riding along in the
     * requester's run. Only allowed for games that are excluded from the global ranking or allow
     * friend scores (Game.allowsFriendScores), and only for the requester and their accepted friends.
     */
    @PostMapping("/upsertbatch")
    fun submitBatchScores(@Valid @RequestBody request: SubmitBatchScoreRequest): List<GameScoreResponse> {
        val requesterId = requireAuth()
        val game = requireNotNull(Game.fromId(request.gameId)) { "Unknown game id: ${request.gameId}" }
        val difficulty = requireNotNull(Difficulty.fromId(request.difficulty)) { "Unknown difficulty: ${request.difficulty}" }
        val scores = request.scores.map { entry ->
            require(ValidationUtils.validateObjectId(entry.userId)) { "Invalid user id: ${entry.userId}" }
            GamesService.BatchScore(userId = ObjectId(entry.userId), score = entry.score, timeMillis = entry.timeMillis)
        }
        return gamesService.submitBatchScores(
            game = game,
            difficulty = difficulty,
            scores = scores,
            requesterId = requesterId,
        ).map { it.toGameScoreResponse() }
    }

    @GetMapping("/highscores")
    fun getHighscores(
        @RequestParam gameid: String,
        @RequestParam(value = "difficulty", defaultValue = "MEDIUM") difficultyId: String,
        @RequestParam(value = "period", defaultValue = "ALL_TIME") periodId: String,
    ): HighscoresResponse {
        val requesterId = requireAuth()
        val game = requireNotNull(Game.fromId(gameid)) { "Unknown game id: $gameid" }
        val difficulty = requireNotNull(Difficulty.fromId(difficultyId)) { "Unknown difficulty: $difficultyId" }
        val period = requireNotNull(LeaderboardPeriod.fromId(periodId)) { "Unknown period: $periodId" }
        return gamesService.getHighscores(game, difficulty, period, requesterId)
    }

    @GetMapping("/globalranking")
    fun getGlobalRanking(
        @RequestParam(value = "period", defaultValue = "ALL_TIME") periodId: String,
    ): GlobalRankingResponse {
        val requesterId = requireAuth()
        val period = requireNotNull(LeaderboardPeriod.fromId(periodId)) { "Unknown period: $periodId" }
        return gamesService.getGlobalRanking(period, requesterId)
    }
}
