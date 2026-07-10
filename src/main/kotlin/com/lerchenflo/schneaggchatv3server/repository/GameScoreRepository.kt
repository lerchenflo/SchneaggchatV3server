package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.games.model.GameScore
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface GameScoreRepository : MongoRepository<GameScore, ObjectId>
