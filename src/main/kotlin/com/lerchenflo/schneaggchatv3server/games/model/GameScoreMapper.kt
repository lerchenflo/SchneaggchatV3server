package com.lerchenflo.schneaggchatv3server.games.model

fun GameScore.toGameScoreResponse(): GameScoreResponse = GameScoreResponse(
    id = id.toHexString(),
    gameId = game.name,
    difficulty = difficulty.name,
    userId = userId.toHexString(),
    score = score,
    timeMillis = timeMillis,
    achievedAt = createdAt.toEpochMilliseconds(),
)
