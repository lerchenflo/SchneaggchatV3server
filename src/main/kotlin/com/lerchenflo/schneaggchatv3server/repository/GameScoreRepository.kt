package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.games.model.Difficulty
import com.lerchenflo.schneaggchatv3server.games.model.Game
import com.lerchenflo.schneaggchatv3server.games.model.GameScore
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface GameScoreRepository : MongoRepository<GameScore, ObjectId> {

    fun findByGame(game: Game): List<GameScore>

    fun findByDifficulty(difficulty: Difficulty): List<GameScore>

    fun findByGameAndDifficulty(game: Game, difficulty: Difficulty): List<GameScore>

    fun findByUserId(userId: ObjectId): List<GameScore>

    fun deleteByUserId(userId: ObjectId)
}
