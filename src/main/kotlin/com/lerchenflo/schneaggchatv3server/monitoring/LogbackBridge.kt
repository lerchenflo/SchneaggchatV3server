package com.lerchenflo.schneaggchatv3server.monitoring

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.AppenderBase
import com.lerchenflo.schneaggchatv3server.util.AppLogLevel
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Feeds WARN/ERROR lines of Spring and libraries (Mongo driver, Firebase, Tomcat, ...) into the
 * monitoring log store, so failures that never pass through [AppLogger] still show up on the
 * dashboard. Logback prints them to the console itself, so they are only queued, not re-printed.
 */
@Component
class LogbackBridge {

    @PostConstruct
    fun install() {
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        if (root.getAppender(APPENDER_NAME) != null) return

        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                if (!event.level.isGreaterOrEqual(Level.WARN)) return
                val throwable = (event.throwableProxy as? ThrowableProxy)?.throwable
                AppLogger.record(
                    level = if (event.level.isGreaterOrEqual(Level.ERROR)) AppLogLevel.ERROR else AppLogLevel.WARN,
                    message = "${event.loggerName.substringAfterLast('.')}: ${event.formattedMessage}",
                    tag = throwable?.let { AppLogger.autoTag(it) } ?: "log@${event.loggerName.substringAfterLast('.')}",
                    throwable = throwable,
                )
            }
        }
        appender.context = context
        appender.name = APPENDER_NAME
        appender.start()
        root.addAppender(appender)
    }

    private companion object {
        const val APPENDER_NAME = "monitoringBridge"
    }
}
