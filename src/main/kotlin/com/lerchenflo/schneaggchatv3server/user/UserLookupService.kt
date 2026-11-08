package com.lerchenflo.schneaggchatv3server.user

import com.lerchenflo.schneaggchatv3server.repository.UserRepository
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.jvm.optionals.getOrNull

/**
 * Simple service for looking up users. Only dependencies are the repository and MongoTemplate, which
 * are always available -> This service can be used in any other service
 */
@Service
class UserLookupService(
    private val userRepository: UserRepository,
    private val mongoTemplate: MongoTemplate,
) {
    init {
        // Lets every log line show the username next to the id
        AppLogger.usernameLookup = { userId -> userRepository.findById(userId).getOrNull()?.username }
    }

    fun checkExistingUser(username: String, email: String) {
        val usernameexists = userRepository.findByUsername(username)
        if (usernameexists != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "A user with this username ($username) already exists")
        }

        if (isEmailTaken(email)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "A user with this email ($email) already exists")
        }
    }

    /** Whether an account other than [exceptUserId] already uses [email]. */
    fun isEmailTaken(email: String, exceptUserId: ObjectId? = null): Boolean {
        val owner = findByEmail(email) ?: return false
        return owner.id != exceptUserId
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
        val optuser = userRepository.findByEmail(ValidationUtils.normalizeEmail(email))

        return optuser
    }

    /**
     * Everyone who turned on police and mobile radar pushes. Off unless a user enabled it.
     * Reads only the ids: a repository projection built a full [User] from the id-only document and
     * failed on the missing fields.
     */
    fun findMapSightingPushUserIds(): List<ObjectId> = mongoTemplate.findDistinct(
        Query(Criteria.where("settings.mapSightingPush").isEqualTo(true)),
        "_id",
        User::class.java,
        ObjectId::class.java,
    )

    fun deleteUser(userId: ObjectId) {
        userRepository.deleteById(userId)
    }

    fun getUsername(userId: ObjectId): String {
        return userRepository.findById(userId).getOrNull()?.username ?: "Unresolved username"
    }
}