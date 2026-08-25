package com.lerchenflo.schneaggchatv3server.user

import com.lerchenflo.schneaggchatv3server.repository.UserRepository
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * Simple service for looking up users. Only dependency is the repository, which is always available
 *  -> This service can be used in any other service
 */
@Service
class UserLookupService(
    private val userRepository: UserRepository,
) {
    fun checkExistingUser(username: String, email: String) {
        val usernameexists = userRepository.findByUsername(username)
        if (usernameexists != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "A user with this username ($username) already exists")
        }

        val emailexists = userRepository.findByEmail(email.trim())
        if (emailexists != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "A user with this email ($email) already exists")
        }
    }

    fun save(user: User): User {
        return userRepository.save(user)
    }

    fun findById(id: String): User? {
        val objid = ObjectId(id)

        return findById(objid)
    }

    fun findById(id: ObjectId): User? {

        val optuser = userRepository.findById(id)

        return if (optuser.isPresent) {
            optuser.get()
        }else null
    }

    fun findAllById(ids: List<ObjectId>): List<User> {
        return userRepository.findAllById(ids).toList()
    }

    fun findAll() : List<User> {
        return userRepository.findAll()
    }

    fun findByUsername(username: String): User? {
        val optuser = userRepository.findByUsername(username)

        return optuser
    }

    fun findByEmail(email: String): User? {
        val optuser = userRepository.findByEmail(email)

        return optuser
    }

    fun deleteUser(userId: ObjectId) {
        userRepository.deleteById(userId)
    }

    fun getUsername(userId: ObjectId): String {
        return userRepository.findById(userId).get().username
    }
}