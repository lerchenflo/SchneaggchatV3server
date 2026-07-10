@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.games.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@TypeAlias("gamescore")
@Document("gamescores")
@CompoundIndex(name = "game_difficulty_score_time_idx", def = "{'game': 1, 'difficulty': 1, 'score': -1, 'timeMillis': 1}")
data class GameScore(
    @Id val id: ObjectId = ObjectId.get(),
    val userId: ObjectId,
    val game: Game,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val score: Long,
    val timeMillis: Long,
    val createdAt: Instant = Clock.System.now(),
)
