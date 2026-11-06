package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.notifications.websocket.PendingPushRegistry
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingPushRegistryTest {

    private val registry = PendingPushRegistry()
    private val device = ObjectId()

    @AfterEach
    fun tearDown() = registry.shutdown()

    @Test
    fun `push fires after the ack timeout when nobody acks`() {
        val latch = CountDownLatch(1)
        registry.await(device, "m1", listOf("s1")) { latch.countDown() }

        assertTrue(latch.await(PendingPushRegistry.ACK_TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `ack cancels the push`() {
        val pushes = AtomicInteger()
        registry.await(device, "m1", listOf("s1")) { pushes.incrementAndGet() }
        registry.ack(device, "m1")

        Thread.sleep(PendingPushRegistry.ACK_TIMEOUT_MS + 500)
        assertEquals(0, pushes.get())
    }

    @Test
    fun `ack from another device does not cancel the push`() {
        val latch = CountDownLatch(1)
        registry.await(device, "m1", listOf("s1")) { latch.countDown() }
        registry.ack(ObjectId(), "m1")

        assertTrue(latch.await(PendingPushRegistry.ACK_TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `closing the only session pushes right away`() {
        val latch = CountDownLatch(1)
        registry.await(device, "m1", listOf("s1")) { latch.countDown() }
        registry.onSessionClosed("s1")

        assertTrue(latch.await(500, TimeUnit.MILLISECONDS), "push should not wait for the timeout")
    }

    @Test
    fun `closing one of two sessions keeps waiting for the other`() {
        val pushes = AtomicInteger()
        registry.await(device, "m1", listOf("s1", "s2")) { pushes.incrementAndGet() }
        registry.onSessionClosed("s1")
        Thread.sleep(300)
        assertEquals(0, pushes.get())

        registry.ack(device, "m1")
        Thread.sleep(PendingPushRegistry.ACK_TIMEOUT_MS + 500)
        assertEquals(0, pushes.get())
    }

    @Test
    fun `push fires exactly once when close and timeout race`() {
        val pushes = AtomicInteger()
        registry.await(device, "m1", listOf("s1")) { pushes.incrementAndGet() }
        Thread.sleep(PendingPushRegistry.ACK_TIMEOUT_MS - 5)
        registry.onSessionClosed("s1")

        Thread.sleep(1_000)
        assertEquals(1, pushes.get())
    }

    @Test
    fun `an ack only cancels the push of its own device`() {
        val otherDevice = ObjectId()
        val pushes = AtomicInteger()
        val otherPushed = CountDownLatch(1)
        registry.await(device, "m1", listOf("s1")) { pushes.incrementAndGet() }
        registry.await(otherDevice, "m1", listOf("s2")) { otherPushed.countDown() }

        registry.ack(device, "m1")

        assertTrue(otherPushed.await(PendingPushRegistry.ACK_TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS))
        assertEquals(0, pushes.get())
    }

    @Test
    fun `closing a session only releases its own device`() {
        val otherDevice = ObjectId()
        val pushes = AtomicInteger()
        val closedPushed = CountDownLatch(1)
        registry.await(device, "m1", listOf("s1")) { closedPushed.countDown() }
        registry.await(otherDevice, "m1", listOf("s2")) { pushes.incrementAndGet() }

        registry.onSessionClosed("s1")

        assertTrue(closedPushed.await(500, TimeUnit.MILLISECONDS))
        assertEquals(0, pushes.get())
    }

    @Test
    fun `shutdown sends what is still held`() {
        val pushes = AtomicInteger()
        registry.await(device, "m1", listOf("s1")) { pushes.incrementAndGet() }

        registry.shutdown()

        assertEquals(1, pushes.get())
    }
}
