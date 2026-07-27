@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.games.GamesService
import com.lerchenflo.schneaggchatv3server.games.GamesService.UserBestScore
import com.lerchenflo.schneaggchatv3server.games.model.Game
import com.lerchenflo.schneaggchatv3server.games.model.LeaderboardPeriod
import org.bson.types.ObjectId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class GamesGlobalRankingTest {

    private fun best(userId: ObjectId) = UserBestScore(
        userId = userId,
        score = 0,
        timeMillis = 0,
        achievedAt = Instant.fromEpochMilliseconds(0),
    )

    /** A best-first board: first user is the board winner. */
    private fun board(game: Game, vararg userIds: ObjectId) =
        game to userIds.map { best(it) }

    private fun pointsOf(rows: List<GamesService.GlobalRankingRow>) =
        rows.associate { it.userId to it.points }


    @Test
    @DisplayName("Points on a board are the share of players beaten")
    fun percentileFormula() {
        val users = List(5) { ObjectId.get() }
        val rows = GamesService.accumulateGlobalPoints(listOf(board(Game.TETRIS, *users.toTypedArray())))
        assertEquals(listOf(100.0, 75.0, 50.0, 25.0, 0.0), rows.map { it.points })
        assertEquals(users, rows.map { it.userId })
    }

    @Test
    @DisplayName("Two-player board: winner 100, loser 0")
    fun twoPlayerBoard() {
        val (winner, loser) = ObjectId.get() to ObjectId.get()
        val rows = GamesService.accumulateGlobalPoints(listOf(board(Game.TOWERSTACK, winner, loser)))
        val points = pointsOf(rows)
        assertEquals(100.0, points[winner])
        assertEquals(0.0, points[loser])
    }


    @Test
    @DisplayName("LeaderboardPeriod.fromId is case-insensitive and null for unknown ids")
    fun periodFromId() {
        for (period in LeaderboardPeriod.entries) {
            assertEquals(period, LeaderboardPeriod.fromId(period.name))
            assertEquals(period, LeaderboardPeriod.fromId(period.name.lowercase()))
        }
        assertNull(LeaderboardPeriod.fromId("does_not_exist"))
        assertNull(LeaderboardPeriod.fromId(""))
    }
}
