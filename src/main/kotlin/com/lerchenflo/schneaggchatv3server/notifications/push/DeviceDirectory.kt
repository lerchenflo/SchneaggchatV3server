package com.lerchenflo.schneaggchatv3server.notifications.push

import com.lerchenflo.schneaggchatv3server.authentication.model.RefreshToken
import com.lerchenflo.schneaggchatv3server.notifications.apns.model.ApnsToken
import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.FirebaseToken
import com.lerchenflo.schneaggchatv3server.repository.ApnsTokenRepository
import com.lerchenflo.schneaggchatv3server.repository.FirebaseTokenRepository
import com.lerchenflo.schneaggchatv3server.repository.RefreshTokenRepository
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import kotlin.jvm.optionals.getOrNull

/**
 * Where a user's devices can be reached. A device is one [RefreshToken] row and its push token sits
 * on that row, so ending a session removes its push target with it. This is the only class that
 * touches the push fields.
 *
 * The `firebasetokens` and `apnstokens` collections are legacy: they can not be matched to a device
 * because they only know the user. A token in there is still pushed until the phone opens the app
 * and registers again, and registering keeps writing it, so a rollback loses nothing. Both go away
 * in phase 7 of docs/UNIFIED_NOTIFICATIONS_PLAN.md.
 */
@Service
class DeviceDirectory(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val firebaseTokenRepository: FirebaseTokenRepository,
    private val apnsTokenRepository: ApnsTokenRepository,
    private val mongoTemplate: MongoTemplate,
    private val loggingService: LoggingService,
) {

    fun targetsOf(userId: ObjectId): List<PushTarget> = targetsOf(listOf(userId)).getOrDefault(userId, emptyList())

    fun targetsOf(userIds: Collection<ObjectId>): Map<ObjectId, List<PushTarget>> {
        val deviceTargets = refreshTokenRepository.findByUserIdInAndPushTokenIsNotNull(userIds).mapNotNull { row ->
            val provider = row.pushProvider ?: return@mapNotNull null
            val token = row.pushToken ?: return@mapNotNull null
            PushTarget(row.userId, provider, token, deviceId = row.id)
        }

        val linkedTokens = deviceTargets.map { it.token }.toSet()
        val legacyTargets = legacyTargetsOf(userIds).filter { it.token !in linkedTokens }

        return (deviceTargets + legacyTargets)
            .filter { it.token.isNotEmpty() }
            .groupBy { it.userId }
    }

    private fun legacyTargetsOf(userIds: Collection<ObjectId>): List<PushTarget> =
        firebaseTokenRepository.findAllByUserIdIn(userIds).map { PushTarget(it.userId, PushProvider.FCM, it.token) } +
            apnsTokenRepository.findAllByUserIdIn(userIds).map { PushTarget(it.userId, PushProvider.APNS, it.token) }

    /**
     * Links [token] to the device that sent it. One token sits on one row only: on an account switch
     * without a clean logout it moves here, so the old account's pushes stop reaching this phone.
     *
     * @param deviceId null for an access token issued before devices existed
     * @return false when the device's session is gone. Its access token stays valid for some minutes,
     * and without this check an app that just got logged out would register itself again.
     */
    fun registerPushToken(userId: ObjectId, deviceId: ObjectId?, provider: PushProvider, token: String): Boolean {
        if (deviceId != null) {
            val row = refreshTokenRepository.findById(deviceId).getOrNull()?.takeIf { it.userId == userId }
                ?: return false

            if (row.pushToken != token) linkToDevice(deviceId, provider, token)
        }

        saveLegacyToken(userId, provider, token)
        return true
    }

    private fun linkToDevice(deviceId: ObjectId, provider: PushProvider, token: String) {
        val onOtherDevices = Query(Criteria.where("pushToken").`is`(token).and("_id").ne(deviceId))
        mongoTemplate.updateMulti(onOtherDevices, UNSET_PUSH_FIELDS, RefreshToken::class.java)

        mongoTemplate.updateFirst(
            Query(Criteria.where("_id").`is`(deviceId)),
            Update().set("pushToken", token).set("pushProvider", provider),
            RefreshToken::class.java,
        )
    }

    private fun saveLegacyToken(userId: ObjectId, provider: PushProvider, token: String) {
        try {
            when (provider) {
                PushProvider.FCM -> {
                    firebaseTokenRepository.deleteByTokenAndUserIdNot(token, userId)
                    if (firebaseTokenRepository.findByUserIdAndToken(userId, token) != null) return
                    firebaseTokenRepository.save(FirebaseToken(userId = userId, token = token))
                    loggingService.log(userId = userId, logType = LogType.FIREBASE_TOKEN_REGISTERED)
                }
                PushProvider.APNS -> {
                    apnsTokenRepository.deleteByTokenAndUserIdNot(token, userId)
                    if (apnsTokenRepository.findByUserIdAndToken(userId, token) != null) return
                    apnsTokenRepository.save(ApnsToken(userId = userId, token = token))
                    loggingService.log(userId = userId, logType = LogType.APNS_TOKEN_REGISTERED)
                }
            }
        } catch (e: DuplicateKeyException) {
            // Two registrations of one phone at the same moment, the first one already saved it
        }
    }

    /** The push service says this token is dead. */
    fun clearPushToken(token: String) {
        mongoTemplate.updateMulti(Query(Criteria.where("pushToken").`is`(token)), UNSET_PUSH_FIELDS, RefreshToken::class.java)
        firebaseTokenRepository.deleteByToken(token)
        apnsTokenRepository.deleteByToken(token)
    }

    /** Logout names the token of its device. Checks the owner: a request must not unregister someone else. */
    fun clearPushToken(userId: ObjectId, token: String) {
        val owned = Query(Criteria.where("userId").`is`(userId).and("pushToken").`is`(token))
        mongoTemplate.updateMulti(owned, UNSET_PUSH_FIELDS, RefreshToken::class.java)
        firebaseTokenRepository.findByUserIdAndToken(userId, token)?.let { firebaseTokenRepository.delete(it) }
        apnsTokenRepository.findByUserIdAndToken(userId, token)?.let { apnsTokenRepository.delete(it) }
    }

    /** The push target of an ended session left with its row, but the legacy copy of the token did not. */
    fun forgetEndedDevices(rows: Collection<RefreshToken>) {
        val tokens = rows.mapNotNull { it.pushToken }
        if (tokens.isEmpty()) return

        firebaseTokenRepository.deleteByTokenIn(tokens)
        apnsTokenRepository.deleteByTokenIn(tokens)
    }

    /** All sessions of [userId] ended, so no legacy token of theirs may stay either. */
    fun forgetLegacyTokensOf(userId: ObjectId) {
        firebaseTokenRepository.deleteAll(firebaseTokenRepository.findAllByUserId(userId))
        apnsTokenRepository.deleteAll(apnsTokenRepository.findAllByUserId(userId))
    }

    private companion object {
        val UNSET_PUSH_FIELDS: Update get() = Update().unset("pushToken").unset("pushProvider")
    }
}
