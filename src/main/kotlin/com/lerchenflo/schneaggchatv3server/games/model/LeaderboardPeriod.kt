package com.lerchenflo.schneaggchatv3server.games.model

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

// Calendar boundaries use the same zone as the recap feature.
private val LEADERBOARD_ZONE: ZoneId = ZoneId.of("Europe/Vienna")

/**
 * Time window a leaderboard is ranked over. Periods are calendar-based
 * (since midnight / Monday / January 1st), not rolling windows.
 */
enum class LeaderboardPeriod {
    DAILY,
    WEEKLY,
    YEARLY,
    ALL_TIME;

    /** Epoch seconds of the period start, or null for [ALL_TIME]. */
    fun startEpochSeconds(now: ZonedDateTime = ZonedDateTime.now(LEADERBOARD_ZONE)): Long? {
        val startOfDay = now.toLocalDate().atStartOfDay(now.zone)
        return when (this) {
            DAILY -> startOfDay.toEpochSecond()
            WEEKLY -> startOfDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochSecond()
            YEARLY -> startOfDay.withDayOfYear(1).toEpochSecond()
            ALL_TIME -> null
        }
    }

    companion object {
        fun fromId(id: String): LeaderboardPeriod? = entries.find { it.name.equals(id, ignoreCase = true) }
    }
}
