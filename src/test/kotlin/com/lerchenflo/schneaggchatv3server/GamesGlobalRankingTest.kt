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
    @DisplayName("Solo player on a board gets 100 points")
    fun soloBoard() {
        val user = ObjectId.get()
        val rows = GamesService.accumulateGlobalPoints(listOf(board(Game.SNAKE, user)))
        assertEquals(1, rows.size)
        assertEquals(100.0, rows.single().points)
        assertEquals(1, rows.single().boardsPlayed)
        assertEquals(1, rows.single().gamesPlayed)
    }

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
    @DisplayName("Points sum across boards; boardsPlayed counts boards, gamesPlayed counts distinct games")
    fun summationAcrossBoards() {
        val a = ObjectId.get()
        val b = ObjectId.get()
        val c = ObjectId.get()
        val rows = GamesService.accumulateGlobalPoints(
            listOf(
                board(Game.SNAKE, a, b),       // a: 100, b: 0
                board(Game.SNAKE, b, a, c),    // b: 100, a: 50, c: 0 (same game, other difficulty)
                board(Game.MORSE, c),          // c: 100
            )
        )
        val byUser = rows.associateBy { it.userId }
        assertEquals(150.0, byUser.getValue(a).points)
        assertEquals(100.0, byUser.getValue(b).points)
        assertEquals(100.0, byUser.getValue(c).points)
        assertEquals(2, byUser.getValue(a).boardsPlayed)
        assertEquals(1, byUser.getValue(a).gamesPlayed)
        assertEquals(2, byUser.getValue(c).boardsPlayed)
        assertEquals(2, byUser.getValue(c).gamesPlayed)
    }

    @Test
    @DisplayName("Empty boards are skipped")
    fun emptyBoards() {
        val user = ObjectId.get()
        val rows = GamesService.accumulateGlobalPoints(
            listOf(
                Game.SNAKE to emptyList(),
                board(Game.TETRIS, user),
                Game.MORSE to emptyList(),
            )
        )
        assertEquals(1, rows.size)
        assertEquals(user, rows.single().userId)

        assertTrue(GamesService.accumulateGlobalPoints(listOf(Game.SNAKE to emptyList())).isEmpty())
        assertTrue(GamesService.accumulateGlobalPoints(emptyList()).isEmpty())
    }

    @Test
    @DisplayName("Result is ordered by total points descending")
    fun orderedByPoints() {
        val a = ObjectId.get()
        val b = ObjectId.get()
        val c = ObjectId.get()
        val rows = GamesService.accumulateGlobalPoints(
            listOf(
                board(Game.SNAKE, c, b, a),    // c: 100, b: 50, a: 0
                board(Game.TETRIS, a, c),      // a: 100, c: 0
                board(Game.GRIDRUSH, a),       // a: 100
            )
        )
        assertEquals(listOf(a, c, b), rows.map { it.userId })
        assertEquals(listOf(200.0, 100.0, 50.0), rows.map { it.points })
    }

    @Test
    @DisplayName("Equal points are tiebroken by more boards played")
    fun tiebreakBoardsPlayed() {
        val soloWinner = ObjectId.get()
        val grinder = ObjectId.get()
        val filler = ObjectId.get()
        val rows = GamesService.accumulateGlobalPoints(
            listOf(
                board(Game.SNAKE, soloWinner),                 // soloWinner: 100 from one board
                board(Game.TETRIS, filler, grinder),           // grinder: 0
                board(Game.MORSE, grinder, filler),            // grinder: 100 -> 100 from two boards
            )
        )
        val ranked = rows.filter { it.userId != filler }.map { it.userId }
        assertEquals(listOf(grinder, soloWinner), ranked)
    }

    @Test
    @DisplayName("Full ties are ordered deterministically by userId")
    fun fullTieDeterministic() {
        val smaller = ObjectId("000000000000000000000001")
        val larger = ObjectId("000000000000000000000002")
        val rows = GamesService.accumulateGlobalPoints(
            listOf(
                board(Game.SNAKE, larger),
                board(Game.TETRIS, smaller),
            )
        )
        assertEquals(listOf(smaller, larger), rows.map { it.userId })
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
