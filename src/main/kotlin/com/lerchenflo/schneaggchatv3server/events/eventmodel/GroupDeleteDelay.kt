package com.lerchenflo.schneaggchatv3server.events.eventmodel

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

// How long after the event's close date (or start date if there's no close date) the
// connected group chat gets auto-deleted. Chosen by the event's creator.
enum class GroupDeleteDelay {
    NEVER,
    ONE_HOUR,
    ONE_DAY,
    THREE_DAYS,
    ONE_WEEK,
}

fun GroupDeleteDelay.toDurationOrNull(): Duration? = when (this) {
    GroupDeleteDelay.NEVER -> null
    GroupDeleteDelay.ONE_HOUR -> 1.hours
    GroupDeleteDelay.ONE_DAY -> 1.days
    GroupDeleteDelay.THREE_DAYS -> 3.days
    GroupDeleteDelay.ONE_WEEK -> 7.days
}
