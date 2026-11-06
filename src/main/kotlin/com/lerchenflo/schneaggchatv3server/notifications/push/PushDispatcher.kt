package com.lerchenflo.schneaggchatv3server.notifications.push

import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.NotificationResponse
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sends pushes on a fixed set of threads. The queue is unbounded on purpose: a burst gets delayed,
 * never dropped. Callers return at once, so a slow push service never blocks a request or a socket.
 */
@Service
class PushDispatcher(
    senders: List<PushSender>,
    private val loggingService: LoggingService,
) {

    private companion object {
        const val THREADS = 8
        const val SHUTDOWN_WAIT_SECONDS = 10L
    }

    private val senderByProvider = senders.associateBy { it.provider }

    private val executor = Executors.newFixedThreadPool(THREADS) { runnable ->
        Thread(runnable, "push-sender").apply { isDaemon = true }
    }

    fun push(targets: Collection<PushTarget>, notification: NotificationResponse, options: PushOptions = PushOptions()) {
        targets.filter { it.provider in options.providers }.forEach { target ->
            executor.execute { send(target, notification, options) }
        }
    }

    private fun send(target: PushTarget, notification: NotificationResponse, options: PushOptions) {
        try {
            senderByProvider.getValue(target.provider).send(target, notification, options)
        } catch (e: Exception) {
            AppLogger.error("[${target.provider}] Push failed: ${e.message}", user = target.userId, error = e, tag = "send_push_failed")
            loggingService.log(
                userId = target.userId,
                logType = LogType.EXCEPTION_THROWN,
                message = "${target.provider} push error: ${e.message}",
            )
        }
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
        executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
    }
}
