@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.games.GamesService.UserBestScore
import com.lerchenflo.schneaggchatv3server.games.model.Difficulty
import com.lerchenflo.schneaggchatv3server.games.model.Game
import org.bson.types.ObjectId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class GamesOrderingTest {

    private fun best(score: Long, timeMillis: Long, achievedAtMillis: Long = 0) = UserBestScore(
        userId = ObjectId.get(),
        score = score,
        timeMillis = timeMillis,
        achievedAt = Instant.fromEpochMilliseconds(achievedAtMillis),
    )

    private fun sorted(game: Game, vararg entries: UserBestScore) =
        entries.toList().shuffled().sortedWith(game.leaderboardComparator())

    @Test
    @DisplayName("Score direction follows each game's higherScoreWins flag")
    fun scoreDirection() {
        for (game in Game.entries) {
            val high = best(score = 100, timeMillis = 50_000)
            val low = best(score = 10, timeMillis = 50_000)
            val expected = if (game.higherScoreWins) listOf(high, low) else listOf(low, high)
            assertEquals(expected, sorted(game, low, high), "Score direction wrong for ${game.name}")
        }
    }

    @Test
    @DisplayName("Equal scores are tiebroken by time in each game's direction")
    fun timeTiebreakDirection() {
        for (game in Game.entries) {
            val fast = best(score = 100, timeMillis = 10_000)
            val slow = best(score = 100, timeMillis = 90_000)
            val expected = if (game.lowerTimeWins) listOf(fast, slow) else listOf(slow, fast)
            assertEquals(expected, sorted(game, fast, slow), "Time tiebreak wrong for ${game.name}")
        }
    }

    @Test
    @DisplayName("Full ties are broken by earliest submission")
    fun fullTieEarliestWins() {
        for (game in Game.entries) {
            val earlier = best(score = 10, timeMillis = 10_000, achievedAtMillis = 1_000)
            val later = best(score = 10, timeMillis = 10_000, achievedAtMillis = 2_000)
            assertEquals(
                listOf(earlier, later),
                sorted(game, later, earlier),
                "Tie in ${game.name} should be won by the earlier submission",
            )
        }
    }

    @Test
    @DisplayName("Game.fromId is case-insensitive and null for unknown ids")
    fun gameFromId() {
        for (game in Game.entries) {
            assertEquals(game, Game.fromId(game.name))
            assertEquals(game, Game.fromId(game.name.lowercase()))
        }
        assertEquals(Game.GAME_2048, Game.fromId("2048"))
        assertEquals(Game.GAME_2048, Game.fromId("game2048"))
        assertEquals(Game.GAME_2048, Game.fromId("GAME-2048"))
        assertNull(Game.fromId("does_not_exist"))
        assertNull(Game.fromId(""))
    }

    @Test
    @DisplayName("Difficulty.fromId is case-insensitive and null for unknown ids")
    fun difficultyFromId() {
        assertEquals(Difficulty.LOW, Difficulty.fromId("low"))
        assertEquals(Difficulty.MEDIUM, Difficulty.fromId("MEDIUM"))
        assertEquals(Difficulty.HIGH, Difficulty.fromId("High"))
        assertNull(Difficulty.fromId("impossible"))
        assertNull(Difficulty.fromId(""))
    }
}
