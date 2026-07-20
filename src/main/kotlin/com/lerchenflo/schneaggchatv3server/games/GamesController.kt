package com.lerchenflo.schneaggchatv3server.games

import com.lerchenflo.schneaggchatv3server.core.security.requireAuth
import com.lerchenflo.schneaggchatv3server.games.model.Difficulty
import com.lerchenflo.schneaggchatv3server.games.model.Game
import com.lerchenflo.schneaggchatv3server.games.model.GameScoreResponse
import com.lerchenflo.schneaggchatv3server.games.model.GlobalRankingResponse
import com.lerchenflo.schneaggchatv3server.games.model.HighscoresResponse
import com.lerchenflo.schneaggchatv3server.games.model.LeaderboardPeriod
import com.lerchenflo.schneaggchatv3server.games.model.toGameScoreResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.*

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
