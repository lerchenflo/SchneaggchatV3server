package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query

interface UserRepository : MongoRepository<User, ObjectId> {

    fun findByUsername(username: String): User?

    fun findByEmail(email: String): User?

    fun findByUsernameContainingIgnoreCase(searchTerm: String): List<User>

    @Query("{ 'birthDate': { \$regex: ?0 } }")
    fun findByBirthDateRegex(regex: String): List<User>

    /** Registration order - backs the admin user list and the friends tree's parent/child orientation. */
    fun findAllByOrderByCreatedAtAsc(): List<User>

}