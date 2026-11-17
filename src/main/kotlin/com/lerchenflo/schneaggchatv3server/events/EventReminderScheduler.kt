package com.lerchenflo.schneaggchatv3server.events

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/** Sends due event reminders, see [EventService.sendDueReminders]. At most about a minute late. */
@Service
class EventReminderScheduler(
    private val eventService: EventService,
) {

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    fun sendDueReminders() {
        eventService.sendDueReminders()
    }
}
