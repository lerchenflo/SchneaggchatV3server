@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.games.model

import kotlin.time.ExperimentalTime

data class GameScoreResponse(
    val id: String,
    val gameId: String,
    val difficulty: String,
    val userId: String,
    val score: Long,
    val timeMillis: Long,
    val achievedAt: Long,
)

fun GameScore.toGameScoreResponse(): GameScoreResponse = GameScoreResponse(
    id = id.toHexString(),
    gameId = game.name,
    difficulty = difficulty.name,
    userId = userId.toHexString(),
    score = score,
    timeMillis = timeMillis,
    achievedAt = createdAt.toEpochMilliseconds(),
)

data class HighscoreEntryResponse(
    val rank: Int,
    val userId: String,
    val username: String,
    val score: Long,
    val timeMillis: Long,
    val achievedAt: Long,
)

data class HighscoresResponse(
    val gameId: String,
    val difficulty: String,
    val period: String,
    // Top 20; the requester is appended with their true rank when placed below that.
    val entries: List<HighscoreEntryResponse>,
)

data class GlobalRankingEntryResponse(
    val rank: Int,
    val userId: String,
    val username: String,
    // Sum of percentile points: up to 100 per (game, difficulty) board played.
    val points: Long,
    val boardsPlayed: Int,
    val gamesPlayed: Int,
)

data class GlobalRankingResponse(
    val period: String,
    // Top 20; the requester is appended with their true rank when placed below that.
    val entries: List<GlobalRankingEntryResponse>,
)
