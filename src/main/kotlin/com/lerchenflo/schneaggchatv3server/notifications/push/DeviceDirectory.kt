package com.lerchenflo.schneaggchatv3server.notifications.push

import com.lerchenflo.schneaggchatv3server.repository.ApnsTokenRepository
import com.lerchenflo.schneaggchatv3server.repository.FirebaseTokenRepository
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/** Where a user's devices can be reached. */
@Service
class DeviceDirectory(
    private val firebaseTokenRepository: FirebaseTokenRepository,
    private val apnsTokenRepository: ApnsTokenRepository,
) {

    fun targetsOf(userId: ObjectId): List<PushTarget> = targetsOf(listOf(userId)).getOrDefault(userId, emptyList())

    fun targetsOf(userIds: Collection<ObjectId>): Map<ObjectId, List<PushTarget>> {
        val fcmTargets = firebaseTokenRepository.findAllByUserIdIn(userIds)
            .map { PushTarget(it.userId, PushProvider.FCM, it.token) }
        val apnsTargets = apnsTokenRepository.findAllByUserIdIn(userIds)
            .map { PushTarget(it.userId, PushProvider.APNS, it.token) }

        return (fcmTargets + apnsTargets)
            .filter { it.token.isNotEmpty() }
            .groupBy { it.userId }
    }
}
