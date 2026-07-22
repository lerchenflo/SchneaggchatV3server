package com.lerchenflo.schneaggchatv3server.wake

import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.notifications.firebase.FirebaseService
import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.NotificationResponse
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsSettingsService
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipStatus
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * Why a wake did or did not reach anybody. The sender is shown this, so a wake that gets
 * filtered out never looks like it silently vanished.
 */
enum class WakeOutcome {
    WOKEN,          // at least one device was dispatched to
    NOT_ALLOWED,    // target(s) have wakes disabled for this sender
    NO_DEVICES,     // allowed, but no Android device is registered
}

data class WakeResponse(
    val outcome: WakeOutcome,
    val userCount: Int,
    val deviceCount: Int,
    val skippedCount: Int,
)

/**
 * Waking plays an alarm ringtone on someone else's phone, so it is opt-in twice over: the target
 * needs their master switch on AND the specific sender enabled. Group members who are not friends
 * with the sender are covered by a trust ratio instead - see [GROUP_TRUST_RATIO].
 */
@Service
class WakeService(
    private val userLookupService: UserLookupService,
    private val groupLookupService: GroupLookupService,
    private val friendsLookupService: FriendsLookupService,
    private val friendsSettingsService: FriendsSettingsService,
    private val firebaseService: FirebaseService,
    private val loggingService: LoggingService,
) {

    companion object {
        /**
         * Fraction of a group (excluding the sender) that must have explicitly allowed the sender
         * to wake them before the sender is also allowed to wake group members they are not
         * friends with. The idea: if half the group already trusts them, they are not a stranger.
         */
        const val GROUP_TRUST_RATIO = 0.5
    }

    fun sendWake(senderId: ObjectId, targetId: ObjectId, isGroup: Boolean, reason: String): WakeResponse {
        val sender = userLookupService.findById(senderId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Sender not found")

        return if (isGroup) {
            sendGroupWake(senderId, sender.username, targetId, reason)
        } else {
            sendUserWake(senderId, sender.username, targetId, reason)
        }
    }

    private fun sendUserWake(senderId: ObjectId, senderName: String, targetId: ObjectId, reason: String): WakeResponse {
        if (senderId == targetId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot wake yourself")
        }

        //Not being friends is a hard error rather than an outcome - there is no legitimate way to
        //reach this from the UI, so it means a malformed or hand-crafted request.
        val friendship = friendsLookupService.findFriendship(senderId, targetId)
        if (friendship == null || friendship.status != FriendshipStatus.ACCEPTED) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "You can only wake friends")
        }

        val target = userLookupService.findById(targetId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Target not found")

        //The target's own setting row decides - the sender has no say in it.
        val allowed = target.allowWakeGlobal &&
                friendsSettingsService.getFriendshipSetting(friendship.id, targetId)?.allowWake == true

        if (!allowed) {
            return WakeResponse(WakeOutcome.NOT_ALLOWED, userCount = 0, deviceCount = 0, skippedCount = 1)
        }

        val deviceCount = firebaseService.getTokensForUser(targetId).count { it.token.isNotEmpty() }
        if (deviceCount == 0) {
            return WakeResponse(WakeOutcome.NO_DEVICES, userCount = 0, deviceCount = 0, skippedCount = 0)
        }

        dispatch(
            recipientId = targetId,
            senderId = senderId,
            senderName = senderName,
            reason = reason,
            groupId = null,
            groupName = "",
            wokenUserCount = 1,
            wokenDeviceCount = deviceCount,
        )

        logWake(senderId, targetId, isGroup = false, reason = reason, deviceCount = deviceCount)
        return WakeResponse(WakeOutcome.WOKEN, userCount = 1, deviceCount = deviceCount, skippedCount = 0)
    }

    private fun sendGroupWake(senderId: ObjectId, senderName: String, groupId: ObjectId, reason: String): WakeResponse {
        val group = groupLookupService.getGroupById(groupId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")

        if (!groupLookupService.isUserInGroup(senderId, groupId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this group")
        }

        val candidates = groupLookupService.getGroupMembers(groupId)
            .map { it.userid }
            .filter { it != senderId }
            .distinct()

        if (candidates.isEmpty()) {
            return WakeResponse(WakeOutcome.NO_DEVICES, userCount = 0, deviceCount = 0, skippedCount = 0)
        }

        //Resolve every member's permission towards the sender once - the trust ratio needs the
        //full picture before any individual decision can be made.
        val permissions = candidates.associateWith { memberId -> resolveMemberPermission(memberId, senderId) }

        val trustRatio = permissions.values.count { it == MemberPermission.ALLOWED }.toDouble() / candidates.size
        val nonFriendsAllowed = trustRatio >= GROUP_TRUST_RATIO

        val recipients = candidates.filter { memberId ->
            when (permissions.getValue(memberId)) {
                MemberPermission.ALLOWED -> true
                MemberPermission.DENIED -> false
                //Not friends with the sender: only reachable if the group as a whole vouches for them.
                MemberPermission.NO_FRIENDSHIP -> nonFriendsAllowed
            }
        }

        //Resolve every recipient's device count BEFORE dispatching: the payload tells each
        //receiver how many people were woken alongside them, which can only be known once the
        //whole recipient set is settled.
        val deviceCounts = recipients.associateWith { memberId ->
            firebaseService.getTokensForUser(memberId).count { it.token.isNotEmpty() }
        }.filterValues { it > 0 }

        val userCount = deviceCounts.size
        val deviceCount = deviceCounts.values.sum()

        deviceCounts.keys.forEach { memberId ->
            dispatch(
                recipientId = memberId,
                senderId = senderId,
                senderName = senderName,
                reason = reason,
                groupId = groupId,
                groupName = group.name,
                wokenUserCount = userCount,
                wokenDeviceCount = deviceCount,
            )
        }

        val skippedCount = candidates.size - recipients.size

        return when {
            userCount > 0 -> {
                logWake(senderId, groupId, isGroup = true, reason = reason, deviceCount = deviceCount)
                WakeResponse(WakeOutcome.WOKEN, userCount, deviceCount, skippedCount)
            }
            skippedCount > 0 -> WakeResponse(WakeOutcome.NOT_ALLOWED, 0, 0, skippedCount)
            else -> WakeResponse(WakeOutcome.NO_DEVICES, 0, 0, skippedCount)
        }
    }

    private enum class MemberPermission { ALLOWED, DENIED, NO_FRIENDSHIP }

    /**
     * Whether [memberId] lets [senderId] wake them. The master switch always wins, so a member
     * with wakes turned off counts as DENIED and never contributes to the trust ratio.
     */
    private fun resolveMemberPermission(memberId: ObjectId, senderId: ObjectId): MemberPermission {
        val member = userLookupService.findById(memberId) ?: return MemberPermission.DENIED
        if (!member.allowWakeGlobal) return MemberPermission.DENIED

        val friendship = friendsLookupService.findFriendship(memberId, senderId)
        if (friendship == null || friendship.status != FriendshipStatus.ACCEPTED) {
            return MemberPermission.NO_FRIENDSHIP
        }

        val allowWake = friendsSettingsService.getFriendshipSetting(friendship.id, memberId)?.allowWake == true
        return if (allowWake) MemberPermission.ALLOWED else MemberPermission.DENIED
    }

    private fun dispatch(
        recipientId: ObjectId,
        senderId: ObjectId,
        senderName: String,
        reason: String,
        groupId: ObjectId?,
        groupName: String,
        wokenUserCount: Int,
        wokenDeviceCount: Int,
    ): Int {
        return firebaseService.sendWakeToUser(
            userId = recipientId,
            notification = NotificationResponse.WakeNotificationResponse(
                senderId = senderId.toHexString(),
                senderName = senderName,
                reason = reason,
                groupId = groupId?.toHexString() ?: "",
                groupName = groupName,
                wokenUserCount = wokenUserCount,
                wokenDeviceCount = wokenDeviceCount,
            )
        )
    }

    private fun logWake(senderId: ObjectId, targetId: ObjectId, isGroup: Boolean, reason: String, deviceCount: Int) {
        loggingService.log(
            userId = senderId,
            logType = LogType.WAKE_SENT,
            message = "Woke ${if (isGroup) "group" else "user"} ${targetId.toHexString()} " +
                    "on $deviceCount device(s): $reason"
        )
    }
}
