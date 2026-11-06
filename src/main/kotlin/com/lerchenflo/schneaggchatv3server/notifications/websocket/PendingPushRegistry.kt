package com.lerchenflo.schneaggchatv3server.notifications.websocket

import com.lerchenflo.schneaggchatv3server.monitoring.ServerMetrics
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import jakarta.annotation.PreDestroy
import org.bson.types.ObjectId
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Push fallback for one device, for what it got over a socket and has to confirm
 * (`messageack`, see [SocketConnectionHandler]).
 *
 * A successful socket write only means the bytes reached the server's TCP buffer, not the app:
 * the app may be closing its socket on the way to the background, frozen by the OS, or the
 * peer already gone without a close frame (radio off - the keepalive sweep only notices after
 * [SocketConnectionHandler.PONG_TIMEOUT]). Treating the write as delivery lost the notification
 * in all those cases. So the push is held here instead and sent when
 * - no session of the device acknowledged it within [ACK_TIMEOUT_MS], or
 * - every session it was written to closed before acknowledging (sent right away, no waiting), or
 * - the server shuts down.
 * One acknowledgement from any session of the device cancels it. Other devices are not affected.
 *
 * Depends on the dispatcher so Spring destroys this bean first, and what it still holds on shutdown
 * can go out.
 */
@Component
@DependsOn("pushDispatcher")
class PendingPushRegistry {

    companion object {
        /** How long a client gets to confirm a message before the push goes out anyway. */
        const val ACK_TIMEOUT_MS = 3_000L
    }

    private data class Key(val deviceId: ObjectId, val deliveryId: String)

    private class Pending(
        /** Sessions the message was written to that have neither acked nor closed yet. */
        val sessionIds: MutableSet<String>,
        val push: () -> Unit,
        val heldAtMs: Long = System.currentTimeMillis(),
    ) {
        var timeout: ScheduledFuture<*>? = null
    }

    private val pending = ConcurrentHashMap<Key, Pending>()

    private val scheduler = Executors.newScheduledThreadPool(2) { runnable ->
        Thread(runnable, "pending-push").apply { isDaemon = true }
    }

    /** Hold [push] for [deviceId] until one of [sessionIds] acks [deliveryId], or fire it as described above. */
    fun await(deviceId: ObjectId, deliveryId: String, sessionIds: Collection<String>, push: () -> Unit) {
        val key = Key(deviceId, deliveryId)
        val entry = Pending(sessionIds.toMutableSet(), push)
        pending[key] = entry
        ServerMetrics.pushesHeld.incrementAndGet()
        entry.timeout = scheduler.schedule({
            if (fire(key, entry)) ServerMetrics.pushesAckTimedOut.incrementAndGet()
        }, ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** The device confirmed it received [deliveryId] - its push is not needed. */
    fun ack(deviceId: ObjectId, deliveryId: String) {
        val entry = pending.remove(Key(deviceId, deliveryId)) ?: return
        entry.timeout?.cancel(false)
        ServerMetrics.pushesAcked.incrementAndGet()
        ServerMetrics.ackLatencyTotalMs.addAndGet(System.currentTimeMillis() - entry.heldAtMs)
    }

    /** [sessionId] closed: everything only it was still waiting on gets pushed now. */
    fun onSessionClosed(sessionId: String) {
        for ((key, entry) in pending) {
            val noSessionLeft = synchronized(entry) {
                entry.sessionIds.remove(sessionId) && entry.sessionIds.isEmpty()
            }
            if (noSessionLeft) {
                entry.timeout?.cancel(false)
                scheduler.execute {
                    if (fire(key, entry)) ServerMetrics.pushesSessionClosed.incrementAndGet()
                }
            }
        }
    }

    /** Sends [entry]'s push unless an ack or a racing fire already took it out of [pending]. */
    private fun fire(key: Key, entry: Pending): Boolean {
        if (!pending.remove(key, entry)) return false

        try {
            entry.push()
        } catch (e: Exception) {
            AppLogger.error("Fallback push failed for device ${key.deviceId}: ${e.message}", error = e)
        }
        return true
    }

    @PreDestroy
    fun shutdown() {
        pending.forEach { (key, entry) ->
            entry.timeout?.cancel(false)
            fire(key, entry)
        }
        scheduler.shutdown()
    }
}
