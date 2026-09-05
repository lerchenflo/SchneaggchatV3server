@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.admin

import com.lerchenflo.schneaggchatv3server.admin.model.ConnectedUserResponse
import com.lerchenflo.schneaggchatv3server.admin.model.ConnectedUsersSnapshot
import com.lerchenflo.schneaggchatv3server.notifications.websocket.SocketConnectionHandler
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.usermodel.UserRole
import org.bson.types.ObjectId
import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.ExperimentalTime

/**
 * Pushes the live "connected users" list to every open admin panel over Server-Sent Events.
 * A browser EventSource cannot set an Authorization header, so the admin client instead consumes
 * this with fetch() + a stream reader - same Bearer auth as every other admin call.
 */
@Service
class AdminEventService(
    // @Lazy breaks the cycle: SocketConnectionHandler -> AdminEventService -> SocketConnectionHandler.
    @Lazy private val socketConnectionHandler: SocketConnectionHandler,
    private val userLookupService: UserLookupService,
) {
    /**
     * Tracks which admin owns each open emitter and when their access token dies, so a revoked admin
     * (or one whose token expired, or who logged out) can be cut off on the next heartbeat instead
     * of staying subscribed until they close the tab - unlike every other admin endpoint, an open
     * SSE connection has no natural per-request re-auth point.
     */
    private data class Subscriber(val emitter: SseEmitter, val userId: ObjectId, val expiresAtMillis: Long)

    private val subscribers = CopyOnWriteArrayList<Subscriber>()

    /**
     * Registers a new emitter and immediately sends the current snapshot. The connection is closed
     * when the access token that opened it expires; the client then reconnects with a fresh one, or
     * is sent back to the login screen if it no longer has one.
     */
    fun register(userId: ObjectId, expiresAtMillis: Long): SseEmitter {
        val emitter = SseEmitter((expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(1L))
        val subscriber = Subscriber(emitter, userId, expiresAtMillis)
        subscribers += subscriber

        emitter.onCompletion { subscribers.remove(subscriber) }
        //Ending the emitter here turns the timeout into an ordinary completion instead of leaving
        //the container to tear the async request down as an error.
        emitter.onTimeout {
            subscribers.remove(subscriber)
            closeQuietly(subscriber)
        }
        emitter.onError { subscribers.remove(subscriber) }

        try {
            emitter.send(SseEmitter.event().name("connected-users").data(buildSnapshot()))
        } catch (e: Exception) {
            subscribers.remove(subscriber)
        }

        return emitter
    }

    fun publishConnectedUsers() {
        if (subscribers.isEmpty()) return

        val snapshot = buildSnapshot()
        val now = System.currentTimeMillis()
        val dead = mutableListOf<Subscriber>()

        for (subscriber in subscribers) {
            //An expired token must not receive another update just because the next heartbeat,
            //which is what normally retires the subscriber, hasn't run yet.
            if (now >= subscriber.expiresAtMillis) {
                closeQuietly(subscriber)
                dead += subscriber
                continue
            }
            try {
                subscriber.emitter.send(SseEmitter.event().name("connected-users").data(snapshot))
            } catch (e: Exception) {
                dead += subscriber
            }
        }

        subscribers.removeAll(dead)
    }

    /**
     * Keeps proxies/load balancers from closing an idle SSE connection, and re-checks each
     * subscriber's token expiry and admin role, so an expired credential or a revoked role is
     * enforced within one heartbeat interval instead of only on the connection's next open.
     */
    @Scheduled(fixedRate = 20_000)
    fun heartbeat() {
        val now = System.currentTimeMillis()
        val dead = mutableListOf<Subscriber>()
        for (subscriber in subscribers) {
            if (now >= subscriber.expiresAtMillis ||
                userLookupService.findById(subscriber.userId)?.role != UserRole.ADMIN
            ) {
                closeQuietly(subscriber)
                dead += subscriber
                continue
            }
            try {
                subscriber.emitter.send(SseEmitter.event().comment("keepalive"))
            } catch (e: Exception) {
                dead += subscriber
            }
        }
        subscribers.removeAll(dead)
    }

    /** complete() throws if the connection already died on its own - that outcome is what we wanted anyway. */
    private fun closeQuietly(subscriber: Subscriber) {
        try {
            subscriber.emitter.complete()
        } catch (e: Exception) {
            // already closed
        }
    }

    fun buildSnapshot(): ConnectedUsersSnapshot {
        val connected = socketConnectionHandler.getConnectedUsers()
        val usernames = userLookupService.findAllById(connected.map { it.userId }).associate { it.id to it.username }

        return ConnectedUsersSnapshot(
            users = connected.map {
                ConnectedUserResponse(
                    userId = it.userId.toHexString(),
                    username = usernames[it.userId] ?: "Unknown",
                    sessionCount = it.sessionCount,
                    onlineSince = it.onlineSince.toEpochMilliseconds(),
                )
            }.sortedBy { it.username.lowercase() }
        )
    }
}
