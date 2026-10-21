@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.monitoring

import com.lerchenflo.schneaggchatv3server.message.MessageLookupService
import com.lerchenflo.schneaggchatv3server.monitoring.model.ServerMetricsSnapshot
import com.lerchenflo.schneaggchatv3server.notifications.websocket.SocketConnectionHandler
import com.lerchenflo.schneaggchatv3server.repository.ServerMetricsRepository
import com.lerchenflo.schneaggchatv3server.repository.UserRepository
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import org.bson.Document
import org.springframework.context.annotation.Lazy
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.lang.management.ManagementFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Samples server state once a minute into `server_metrics` for the dashboard's status charts.
 * Kept as long as the logs ([AppLogService.RETENTION]).
 */
@Service
class ServerMetricsService(
    private val serverMetricsRepository: ServerMetricsRepository,
    private val mongoTemplate: MongoTemplate,
    private val redisTemplate: StringRedisTemplate,
    // @Lazy: SocketConnectionHandler pulls in most of the app, this service must not take part in its cycles.
    @Lazy private val socketConnectionHandler: SocketConnectionHandler,
    private val messageLookupService: MessageLookupService,
    private val userRepository: UserRepository,
    private val loggingService: LoggingService,
) {
    // Totals of the previous sample - message / account counts and GC counters are cumulative,
    // the snapshot stores the per-minute difference. null until the first sample.
    private var lastMessageCount: Long? = null
    private var lastUserCount: Long? = null
    private var lastGcCount = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount.coerceAtLeast(0) }
    private var lastGcTimeMs = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) }

    @Scheduled(fixedRate = 60_000, initialDelay = 60_000)
    fun sample() {
        try {
            serverMetricsRepository.save(takeSnapshot())
        } catch (e: Exception) {
            AppLogger.warn("Could not sample server metrics: ${e.message}", tag = "sample_server_metrics_failed", error = e)
        }
    }

    fun deleteExpired() {
        serverMetricsRepository.deleteByTimestampBefore(Clock.System.now() - AppLogService.RETENTION)
    }

    private fun takeSnapshot(): ServerMetricsSnapshot {
        val memory = ManagementFactory.getMemoryMXBean()
        val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
        val gcCount = gcBeans.sumOf { it.collectionCount.coerceAtLeast(0) }
        val gcTimeMs = gcBeans.sumOf { it.collectionTime.coerceAtLeast(0) }

        val messageCount = messageLookupService.count()
        val userCount = userRepository.count()
        val connected = socketConnectionHandler.getConnectedUsers()

        val snapshot = ServerMetricsSnapshot(
            timestamp = Clock.System.now(),
            heapUsedMb = memory.heapMemoryUsage.used.toMb(),
            heapMaxMb = memory.heapMemoryUsage.max.let { if (it > 0) it else memory.heapMemoryUsage.committed }.toMb(),
            nonHeapUsedMb = memory.nonHeapMemoryUsage.used.toMb(),
            processCpuPercent = os?.processCpuLoad.toPercent(),
            systemCpuPercent = os?.cpuLoad.toPercent(),
            threadCount = ManagementFactory.getThreadMXBean().threadCount,
            gcCount = gcCount - lastGcCount,
            gcTimeMs = gcTimeMs - lastGcTimeMs,
            uptimeSeconds = ManagementFactory.getRuntimeMXBean().uptime / 1000,
            socketConnections = connected.sumOf { it.sessionCount },
            socketUsers = connected.size,
            fcmSent = ServerMetrics.fcmSent.getAndSet(0),
            fcmFailed = ServerMetrics.fcmFailed.getAndSet(0),
            apnsSent = ServerMetrics.apnsSent.getAndSet(0),
            apnsFailed = ServerMetrics.apnsFailed.getAndSet(0),
            emailsSent = ServerMetrics.emailsSent.getAndSet(0),
            emailsFailed = ServerMetrics.emailsFailed.getAndSet(0),
            // Deletions shrink the total, so a minute with more deletes than sends would go negative.
            messagesSent = lastMessageCount?.let { (messageCount - it).coerceAtLeast(0) } ?: 0,
            newAccounts = lastUserCount?.let { (userCount - it).coerceAtLeast(0) } ?: 0,
            logins = ServerMetrics.logins.getAndSet(0),
            activeDevices = loggingService.getActiveDeviceCount(),
            mongoPingMs = timedPing { mongoTemplate.db.runCommand(Document("ping", 1)) },
            redisPingMs = timedPing { redisTemplate.requiredConnectionFactory.connection.use { it.ping() } },
            logsDropped = AppLogger.dropped.getAndSet(0),
        )

        lastMessageCount = messageCount
        lastUserCount = userCount
        lastGcCount = gcCount
        lastGcTimeMs = gcTimeMs
        return snapshot
    }

    private fun Long.toMb(): Double = this / (1024.0 * 1024.0)

    private fun Double?.toPercent(): Double = if (this == null || this < 0) -1.0 else this * 100

    private fun timedPing(ping: () -> Unit): Double = try {
        val start = System.nanoTime()
        ping()
        (System.nanoTime() - start) / 1_000_000.0
    } catch (e: Exception) {
        -1.0
    }
}
