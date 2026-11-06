package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.notifications.Delivery
import com.lerchenflo.schneaggchatv3server.notifications.DeliveryService
import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.NotificationResponse
import com.lerchenflo.schneaggchatv3server.notifications.push.DeviceDirectory
import com.lerchenflo.schneaggchatv3server.notifications.push.PushDispatcher
import com.lerchenflo.schneaggchatv3server.notifications.push.PushOptions
import com.lerchenflo.schneaggchatv3server.notifications.push.PushProvider
import com.lerchenflo.schneaggchatv3server.notifications.push.PushTarget
import com.lerchenflo.schneaggchatv3server.notifications.websocket.PendingPushRegistry
import com.lerchenflo.schneaggchatv3server.notifications.websocket.SocketConnectionHandler
import com.lerchenflo.schneaggchatv3server.notifications.websocket.model.SocketConnection
import com.lerchenflo.schneaggchatv3server.notifications.websocket.model.SocketConnectionMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test

/** The decision table: what reaches the sockets and the push services in each situation. */
class DeliveryServiceTest {

    private val sockets = mockk<SocketConnectionHandler>(relaxed = true)
    private val deviceDirectory = mockk<DeviceDirectory>()
    private val pushDispatcher = mockk<PushDispatcher>(relaxed = true)
    private val pendingPushRegistry = mockk<PendingPushRegistry>(relaxed = true)

    private val service = DeliveryService(sockets, deviceDirectory, pushDispatcher, pendingPushRegistry)

    private val user = ObjectId()
    private val socket = SocketConnectionMessage.FriendOnlineStatusSnapshot(emptyList())
    private val notification = NotificationResponse.SystemNotificationResponse("title", "text")
    private val target = PushTarget(user, PushProvider.FCM, "token")

    private fun session(id: String, acks: Boolean = true, writeWorks: Boolean = true): SocketConnection {
        val connection = SocketConnection(sessionId = id, userId = user, session = mockk(relaxed = true), acksMessages = acks)
        every { sockets.write(connection, any()) } returns writeWorks
        return connection
    }

    private fun userHas(vararg sessions: SocketConnection) {
        every { sockets.sessionsOf(user) } returns sessions.toList()
        every { deviceDirectory.targetsOf(any<Collection<ObjectId>>()) } returns mapOf(user to listOf(target))
    }

    private fun verifyPushed() = verify(exactly = 1) { pushDispatcher.push(listOf(target), notification, any()) }

    private fun verifyNotPushed() = verify(exactly = 0) { pushDispatcher.push(any(), any(), any()) }

    @Test
    fun `live writes to every session and never reads push targets`() {
        val first = session("a")
        val second = session("b")
        every { sockets.sessionsOf(user) } returns listOf(first, second)

        service.send(user, Delivery.Live(socket))

        verify(exactly = 1) { sockets.write(first, any()) }
        verify(exactly = 1) { sockets.write(second, any()) }
        verify(exactly = 0) { deviceDirectory.targetsOf(any<Collection<ObjectId>>()) }
        verifyNotPushed()
    }

    @Test
    fun `push only ignores the sockets`() {
        userHas(session("a"))
        val options = PushOptions(ttlSeconds = 0)

        service.send(user, Delivery.PushOnly(notification, options))

        verify(exactly = 1) { pushDispatcher.push(listOf(target), notification, options) }
        verify(exactly = 0) { sockets.write(any(), any()) }
    }

    @Test
    fun `alert without a session pushes right away`() {
        userHas()

        service.send(user, Delivery.Alert(socket, notification))

        verifyPushed()
    }

    @Test
    fun `alert without ack counts a socket write as delivered`() {
        userHas(session("a"))

        service.send(user, Delivery.Alert(socket, notification))

        verifyNotPushed()
    }

    @Test
    fun `alert pushes when every write fails`() {
        userHas(session("a", writeWorks = false))

        service.send(user, Delivery.Alert(socket, notification))

        verifyPushed()
    }

    @Test
    fun `alert waiting for an ack holds the push before writing`() {
        val connection = session("a")
        userHas(connection)
        val held = slot<() -> Unit>()

        service.send(user, Delivery.Alert(socket, notification, id = "m1", awaitAck = true))

        verify(exactly = 1) { pendingPushRegistry.await(user, "m1", listOf("a"), capture(held)) }
        verifyNotPushed()

        held.captured()
        verifyPushed()
    }

    @Test
    fun `a session of an app without acks counts as delivered`() {
        userHas(session("a", acks = false))

        service.send(user, Delivery.Alert(socket, notification, awaitAck = true))

        verify(exactly = 0) { pendingPushRegistry.await(any(), any(), any(), any()) }
        verifyNotPushed()
    }

    @Test
    fun `every session gets the write after the first one worked`() {
        val first = session("a", acks = false)
        val second = session("b", acks = false)
        userHas(first, second)

        service.send(user, Delivery.Alert(socket, notification))

        verify(exactly = 1) { sockets.write(second, any()) }
    }

    @Test
    fun `send all reads the push targets of everyone in one query`() {
        val other = ObjectId()
        every { sockets.sessionsOf(any()) } returns emptyList()
        every { deviceDirectory.targetsOf(any<Collection<ObjectId>>()) } returns emptyMap()

        service.sendAll(listOf(user, other)) { Delivery.Alert(socket, notification) }

        verify(exactly = 1) { deviceDirectory.targetsOf(any<Collection<ObjectId>>()) }
    }
}
