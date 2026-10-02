package com.lerchenflo.schneaggchatv3server.notifications.websocket

import com.lerchenflo.schneaggchatv3server.util.AppLogger
import jakarta.annotation.PreDestroy
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Push fallback for new messages delivered over a socket whose client confirms receipt
 * (`messageack`, see [SocketConnectionHandler]).
 *
 * A successful socket write only means the bytes reached the server's TCP buffer, not the app:
 * the app may be closing its socket on the way to the background, frozen by the OS, or the
 * peer already gone without a close frame (radio off - the keepalive sweep only notices after
 * [SocketConnectionHandler.PONG_TIMEOUT]). Treating the write as delivery lost the notification
 * in all those cases. So the push is held here instead and sent when
 * - no session acknowledged the message within [ACK_TIMEOUT_MS], or
 * - every session it was written to closed before acknowledging (sent right away, no waiting).
 * One acknowledgement from any of the sessions cancels it.
 */
@Component
class PendingPushRegistry {

    companion object {
        /** How long a client gets to confirm a message before the push goes out anyway. */
        const val ACK_TIMEOUT_MS = 3_000L
    }

    private data class Key(val userId: ObjectId, val messageId: String)

    private class Pending(
        /** Sessions the message was written to that have neither acked nor closed yet. */
        val sessionIds: MutableSet<String>,
        val push: () -> Unit,
    ) {
        var timeout: ScheduledFuture<*>? = null
    }

    private val pending = ConcurrentHashMap<Key, Pending>()

    // Pushes run here too, so a slow FCM/APNs call never blocks a socket or request thread
    private val scheduler = Executors.newScheduledThreadPool(2) { runnable ->
        Thread(runnable, "pending-push").apply { isDaemon = true }
    }

    /** Hold [push] for [userId] until one of [sessionIds] acks [messageId], or fire it as described above. */
    fun await(userId: ObjectId, messageId: String, sessionIds: Collection<String>, push: () -> Unit) {
        if (sessionIds.isEmpty()) {
            scheduler.execute { runPush(push) }
            return
        }
        val key = Key(userId, messageId)
        val entry = Pending(sessionIds.toMutableSet(), push)
        pending[key] = entry
        entry.timeout = scheduler.schedule({ fire(key, entry) }, ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** The client confirmed it received [messageId] - its push is not needed. */
    fun ack(userId: ObjectId, messageId: String) {
        pending.remove(Key(userId, messageId))?.timeout?.cancel(false)
    }

    /** [sessionId] closed: everything only it was still waiting on gets pushed now. */
    fun onSessionClosed(sessionId: String) {
        for ((key, entry) in pending) {
            val noSessionLeft = synchronized(entry) {
                entry.sessionIds.remove(sessionId) && entry.sessionIds.isEmpty()
            }
            if (noSessionLeft) {
                entry.timeout?.cancel(false)
                scheduler.execute { fire(key, entry) }
            }
        }
    }

    /** Sends [entry]'s push unless an ack or a racing fire already took it out of [pending]. */
    private fun fire(key: Key, entry: Pending) {
        if (pending.remove(key, entry)) runPush(entry.push)
    }

    private fun runPush(push: () -> Unit) {
        try {
            push()
        } catch (e: Exception) {
            AppLogger.error("Fallback push failed: ${e.message}")
        }
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdown()
    }
}
