package com.lerchenflo.schneaggchatv3server.notifications

import com.lerchenflo.schneaggchatv3server.monitoring.ServerMetrics
import com.lerchenflo.schneaggchatv3server.notifications.push.DeviceDirectory
import com.lerchenflo.schneaggchatv3server.notifications.push.PushDispatcher
import com.lerchenflo.schneaggchatv3server.notifications.push.PushProvider
import com.lerchenflo.schneaggchatv3server.notifications.push.PushTarget
import com.lerchenflo.schneaggchatv3server.notifications.websocket.PendingPushRegistry
import com.lerchenflo.schneaggchatv3server.notifications.websocket.SocketConnectionHandler
import com.lerchenflo.schneaggchatv3server.notifications.websocket.model.SocketConnection
import com.lerchenflo.schneaggchatv3server.notifications.websocket.model.SocketConnectionMessage
import com.lerchenflo.schneaggchatv3server.util.Json
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * The only place that decides between socket and push. Callers describe what a user should get
 * with a [Delivery]; everything about sessions, push tokens and acks stays in here.
 */
@Service
class DeliveryService(
    private val sockets: SocketConnectionHandler,
    private val deviceDirectory: DeviceDirectory,
    private val pushDispatcher: PushDispatcher,
    private val pendingPushRegistry: PendingPushRegistry,
) {

    fun send(userId: ObjectId, delivery: Delivery) = sendAll(listOf(userId)) { delivery }

    /** Like [send] for many users. Reads the push targets of all of them in one query. */
    fun sendAll(userIds: Collection<ObjectId>, build: (ObjectId) -> Delivery) {
        val deliveries = userIds.associateWith(build)

        val pushRecipients = deliveries.filterValues { it !is Delivery.Live }.keys
        val targets = if (pushRecipients.isEmpty()) emptyMap() else deviceDirectory.targetsOf(pushRecipients)

        deliveries.forEach { (userId, delivery) ->
            when (delivery) {
                is Delivery.Live -> write(sockets.sessionsOf(userId), delivery.socket)
                is Delivery.Alert -> deliverAlert(userId, delivery, targets[userId].orEmpty())
                is Delivery.PushOnly -> pushDispatcher.push(targets[userId].orEmpty(), delivery.push, delivery.options)
            }
        }
    }

    /** To one session only, for what a device needs right after it connected. */
    fun sendToSession(sessionId: String, socket: SocketConnectionMessage) {
        write(sockets.connections.filter { it.sessionId == sessionId }, socket)
    }

    /** To every connected user, whatever their friends or groups. */
    fun broadcast(socket: SocketConnectionMessage, excludeUserId: ObjectId?) {
        write(sockets.connections.filter { it.userId != excludeUserId }, socket)
    }

    fun isConnected(userId: ObjectId): Boolean = sockets.isConnected(userId)

    fun pushDeviceCount(userId: ObjectId, provider: PushProvider): Int =
        deviceDirectory.targetsOf(userId).count { it.provider == provider }

    /**
     * Decided per device, whatever the other devices of the user do: a phone in the pocket gets its
     * push while the desktop app is open.
     */
    private fun deliverAlert(userId: ObjectId, alert: Delivery.Alert, targets: List<PushTarget>) {
        val sessions = sockets.sessionsOf(userId)

        val sessionsByDevice = sessions.filter { it.deviceId != null }.groupBy { it.deviceId!! }
        val targetsByDevice = targets.filter { it.deviceId != null }.groupBy { it.deviceId!! }

        (sessionsByDevice.keys + targetsByDevice.keys).forEach { deviceId ->
            deliverToDevice(deviceId, alert, sessionsByDevice[deviceId].orEmpty(), targetsByDevice[deviceId].orEmpty())
        }

        // Neither side knows its device yet (see DeviceDirectory): sessions only get the write, and
        // a legacy token always gets the push, so nobody misses anything.
        write(sessions.filter { it.deviceId == null }, alert.socket)
        pushNow(targets.filter { it.deviceId == null }, alert)
    }

    private fun deliverToDevice(deviceId: ObjectId, alert: Delivery.Alert, sessions: List<SocketConnection>, targets: List<PushTarget>) {
        when {
            sessions.isEmpty() -> pushNow(targets, alert)

            targets.isEmpty() -> write(sessions, alert.socket) // desktop, it has no push

            alert.awaitAck -> {
                // Registered before the write, so an ack can never arrive ahead of it. A failed
                // write closes its session, and that releases the push.
                pendingPushRegistry.await(deviceId, alert.id, sessions.map { it.sessionId }) {
                    pushDispatcher.push(targets, alert.push, alert.options)
                }
                write(sessions, alert.socket)
            }

            else -> if (!write(sessions, alert.socket)) pushNow(targets, alert)
        }
    }

    private fun pushNow(targets: List<PushTarget>, alert: Delivery.Alert) {
        if (targets.isEmpty()) return

        ServerMetrics.pushesImmediate.incrementAndGet()
        pushDispatcher.push(targets, alert.push, alert.options)
    }

    /** @return whether at least one session took it. A session whose write fails gets closed. */
    private fun write(sessions: List<SocketConnection>, socket: SocketConnectionMessage): Boolean {
        if (sessions.isEmpty()) return false

        val json = Json.mapper.writeValueAsString(socket)
        // Not any { }: it would stop at the first success and starve the other sessions
        return sessions.map { sockets.write(it, json) }.any { it }
    }
}
