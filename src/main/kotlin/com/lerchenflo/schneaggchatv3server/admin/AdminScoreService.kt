@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.admin

import com.lerchenflo.schneaggchatv3server.games.model.Difficulty
import com.lerchenflo.schneaggchatv3server.games.model.Game
import com.lerchenflo.schneaggchatv3server.repository.GameScoreRepository
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.time.ExperimentalTime

data class AdminScoreResponse(
    val id: String,
    val userId: String,
    val username: String,
    val game: Game,
    val difficulty: Difficulty,
    val score: Long,
    val timeMillis: Long,
    val createdAt: Long,
)

data class AdminScorePage(
    val entries: List<AdminScoreResponse>,
    val moreEntries: Boolean,
)

enum class ScoreSort { DATE, GAME, USER, SCORE }

/**
 * Admin read/write path for game scores. Deletes are hard - `GameScore` has no soft-delete flag,
 * and a row is only ever removed here because it shouldn't count, so leaving it in the collection
 * would keep polluting the leaderboard aggregations in `GamesService`.
 */
@Service
class AdminScoreService(
    private val gameScoreRepository: GameScoreRepository,
    private val userLookupService: UserLookupService,
) {

    /**
     * Filters in Mongo, then resolves usernames and sorts/pages in memory - sorting by username is
     * not expressible as a Mongo sort (the name lives on `users`, not `gamescores`), and doing it
     * this way keeps one code path for all four sort keys. Same shape as
     * `SchneaggmapService.mapSync`, which already pages an in-memory list.
     */
    fun getScores(
        game: Game?,
        difficulty: Difficulty?,
        userId: ObjectId?,
        sort: ScoreSort,
        page: Int,
        pageSize: Int,
    ): AdminScorePage {
        val filtered = when {
            userId != null -> gameScoreRepository.findByUserId(userId)
                .filter { (game == null || it.game == game) && (difficulty == null || it.difficulty == difficulty) }
            game != null && difficulty != null -> gameScoreRepository.findByGameAndDifficulty(game, difficulty)
            game != null -> gameScoreRepository.findByGame(game)
            difficulty != null -> gameScoreRepository.findByDifficulty(difficulty)
            else -> gameScoreRepository.findAll()
        }

        val usernames = userLookupService
            .findAllById(filtered.map { it.userId }.distinct())
            .associate { it.id to it.username }

        val responses = filtered.map { entry ->
            AdminScoreResponse(
                id = entry.id.toHexString(),
                userId = entry.userId.toHexString(),
                username = usernames[entry.userId] ?: "Unknown",
                game = entry.game,
                difficulty = entry.difficulty,
                score = entry.score,
                timeMillis = entry.timeMillis,
                createdAt = entry.createdAt.toEpochMilliseconds(),
            )
        }

        val sorted = when (sort) {
            ScoreSort.DATE -> responses.sortedByDescending { it.createdAt }
            ScoreSort.GAME -> responses.sortedWith(
                compareBy<AdminScoreResponse> { it.game.name }
                    .thenBy { it.difficulty.ordinal }
                    .thenByDescending { it.score }
            )
            ScoreSort.USER -> responses.sortedWith(
                compareBy<AdminScoreResponse> { it.username.lowercase() }
                    .thenBy { it.game.name }
                    .thenByDescending { it.score }
            )
            ScoreSort.SCORE -> responses.sortedWith(
                compareByDescending<AdminScoreResponse> { it.score }.thenBy { it.timeMillis }
            )
        }

        val start = page * pageSize
        return AdminScorePage(
            entries = sorted.drop(start).take(pageSize),
            moreEntries = (start + pageSize) < sorted.size,
        )
    }

    /**
     * Only `score` and `timeMillis` are editable. Game, difficulty and owner stay fixed - changing
     * those would move the entry onto a board it was never played on, or onto another account.
     */
    fun updateScore(id: ObjectId, score: Long, timeMillis: Long): AdminScoreResponse {
        require(score >= 0) { "Score must not be negative" }
        require(timeMillis >= 0) { "Time must not be negative" }

        val existing = gameScoreRepository.findById(id).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Score not found")

        val saved = gameScoreRepository.save(existing.copy(score = score, timeMillis = timeMillis))

        return AdminScoreResponse(
            id = saved.id.toHexString(),
            userId = saved.userId.toHexString(),
            username = userLookupService.getUsername(saved.userId),
            game = saved.game,
            difficulty = saved.difficulty,
            score = saved.score,
            timeMillis = saved.timeMillis,
            createdAt = saved.createdAt.toEpochMilliseconds(),
        )
    }

    fun deleteScore(id: ObjectId) {
        if (!gameScoreRepository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Score not found")
        }
        gameScoreRepository.deleteById(id)
    }
}
