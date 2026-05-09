package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface UserRepository : MongoRepository<User, ObjectId> {

    fun findByUsername(username: String): User?

    fun findByEmail(email: String): User?

    fun findByUsernameContainingIgnoreCase(searchTerm: String): List<User>

}