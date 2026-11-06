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
    private val phone = ObjectId()
    private val secondPhone = ObjectId()
    private val desktop = ObjectId()

    private val socket = SocketConnectionMessage.FriendOnlineStatusSnapshot(emptyList())
    private val notification = NotificationResponse.SystemNotificationResponse("title", "text")

    private fun session(id: String, device: ObjectId?, writeWorks: Boolean = true): SocketConnection {
        val connection = SocketConnection(sessionId = id, userId = user, session = mockk(relaxed = true), deviceId = device)
        every { sockets.write(connection, any()) } returns writeWorks
        return connection
    }

    private fun target(token: String, device: ObjectId?) = PushTarget(user, PushProvider.FCM, token, deviceId = device)

    private fun userHas(sessions: List<SocketConnection>, targets: List<PushTarget>) {
        every { sockets.sessionsOf(user) } returns sessions
        every { deviceDirectory.targetsOf(any<Collection<ObjectId>>()) } returns mapOf(user to targets)
    }

    private fun verifyPushed(vararg targets: PushTarget) =
        verify(exactly = 1) { pushDispatcher.push(targets.toList(), notification, any()) }

    private fun verifyNotPushed() = verify(exactly = 0) { pushDispatcher.push(any(), any(), any()) }

    private fun verifyNothingHeld() = verify(exactly = 0) { pendingPushRegistry.await(any(), any(), any(), any()) }

    @Test
    fun `live writes to every session and never reads push targets`() {
        val first = session("a", phone)
        val second = session("b", desktop)
        every { sockets.sessionsOf(user) } returns listOf(first, second)

        service.send(user, Delivery.Live(socket))

        verify(exactly = 1) { sockets.write(first, any()) }
        verify(exactly = 1) { sockets.write(second, any()) }
        verify(exactly = 0) { deviceDirectory.targetsOf(any<Collection<ObjectId>>()) }
        verifyNotPushed()
    }

    @Test
    fun `push only ignores the sockets`() {
        val phoneTarget = target("token", phone)
        userHas(listOf(session("a", phone)), listOf(phoneTarget))
        val options = PushOptions(ttlSeconds = 0)

        service.send(user, Delivery.PushOnly(notification, options))

        verify(exactly = 1) { pushDispatcher.push(listOf(phoneTarget), notification, options) }
        verify(exactly = 0) { sockets.write(any(), any()) }
    }

    @Test
    fun `a device without a session gets the push right away`() {
        val phoneTarget = target("token", phone)
        userHas(emptyList(), listOf(phoneTarget))

        service.send(user, Delivery.Alert(socket, notification))

        verifyPushed(phoneTarget)
        verifyNothingHeld()
    }

    @Test
    fun `the main bug - an open desktop does not silence the phone`() {
        val desktopSession = session("d", desktop)
        val phoneTarget = target("token", phone)
        userHas(listOf(desktopSession), listOf(phoneTarget))

        service.send(user, Delivery.Alert(socket, notification, awaitAck = true))

        verify(exactly = 1) { sockets.write(desktopSession, any()) }
        verifyPushed(phoneTarget)
        verifyNothingHeld()
    }

    @Test
    fun `a phone in the foreground does not silence the second phone`() {
        val foreground = session("a", phone)
        val phoneTarget = target("one", phone)
        val pocketTarget = target("two", secondPhone)
        userHas(listOf(foreground), listOf(phoneTarget, pocketTarget))
        val held = slot<() -> Unit>()

        service.send(user, Delivery.Alert(socket, notification, id = "m1", awaitAck = true))

        verifyPushed(pocketTarget)
        verify(exactly = 1) { pendingPushRegistry.await(phone, "m1", listOf("a"), capture(held)) }

        held.captured()
        verifyPushed(phoneTarget)
    }

    @Test
    fun `a device that has to ack holds its push before the write`() {
        val connection = session("a", phone)
        val phoneTarget = target("token", phone)
        userHas(listOf(connection), listOf(phoneTarget))

        service.send(user, Delivery.Alert(socket, notification, id = "m1", awaitAck = true))

        verifyNotPushed()
        verify(ordering = io.mockk.Ordering.ORDERED) {
            pendingPushRegistry.await(phone, "m1", listOf("a"), any())
            sockets.write(connection, any())
        }
    }

    @Test
    fun `all sessions of a device wait for the same ack`() {
        userHas(listOf(session("a", phone), session("b", phone)), listOf(target("token", phone)))

        service.send(user, Delivery.Alert(socket, notification, id = "m1", awaitAck = true))

        verify(exactly = 1) { pendingPushRegistry.await(phone, "m1", listOf("a", "b"), any()) }
    }

    @Test
    fun `a desktop has no push, so nothing is held for it`() {
        val desktopSession = session("d", desktop)
        userHas(listOf(desktopSession), emptyList())

        service.send(user, Delivery.Alert(socket, notification, awaitAck = true))

        verify(exactly = 1) { sockets.write(desktopSession, any()) }
        verifyNothingHeld()
        verifyNotPushed()
    }

    @Test
    fun `without an ack a socket write counts as delivered for its device`() {
        userHas(listOf(session("a", phone)), listOf(target("token", phone)))

        service.send(user, Delivery.Alert(socket, notification))

        verifyNothingHeld()
        verifyNotPushed()
    }

    @Test
    fun `without an ack a failed write falls back to the push`() {
        val phoneTarget = target("token", phone)
        userHas(listOf(session("a", phone, writeWorks = false)), listOf(phoneTarget))

        service.send(user, Delivery.Alert(socket, notification))

        verifyPushed(phoneTarget)
    }

    @Test
    fun `every session of a device gets the write after the first one worked`() {
        val second = session("b", phone)
        userHas(listOf(session("a", phone), second), listOf(target("token", phone)))

        service.send(user, Delivery.Alert(socket, notification))

        verify(exactly = 1) { sockets.write(second, any()) }
    }

    @Test
    fun `a legacy token is always pushed`() {
        val legacy = target("old", null)
        userHas(listOf(session("a", phone)), listOf(legacy))

        service.send(user, Delivery.Alert(socket, notification, awaitAck = true))

        verifyPushed(legacy)
    }

    @Test
    fun `a session without a device only gets the write`() {
        val unknown = session("a", null)
        userHas(listOf(unknown), emptyList())

        service.send(user, Delivery.Alert(socket, notification, awaitAck = true))

        verify(exactly = 1) { sockets.write(unknown, any()) }
        verifyNothingHeld()
        verifyNotPushed()
    }

    @Test
    fun `send all reads the push targets of everyone in one query`() {
        val other = ObjectId()
        every { sockets.sessionsOf(any()) } returns emptyList()
        every { deviceDirectory.targetsOf(any<Collection<ObjectId>>()) } returns emptyMap()

        service.sendAll(listOf(user, other)) { Delivery.Alert(socket, notification) }

        verify(exactly = 1) { deviceDirectory.targetsOf(any<Collection<ObjectId>>()) }
    }

    @Test
    fun `send to session reaches only that session`() {
        val wanted = session("a", phone)
        val other = session("b", desktop)
        every { sockets.connections } returns java.util.concurrent.CopyOnWriteArrayList(listOf(wanted, other))

        service.sendToSession("a", socket)

        verify(exactly = 1) { sockets.write(wanted, any()) }
        verify(exactly = 0) { sockets.write(other, any()) }
    }
}
