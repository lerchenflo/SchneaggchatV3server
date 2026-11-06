@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeValue
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntry
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Enum names are wire names in the map_sighting push and must match the client. */
enum class MapSightingType { POLICE, RADAR }

private val SIGHTING_MAX_AGE = 1.hours

//A phone's clock can run a little ahead. Anything further out is a date picked by mistake, not a sighting.
private val SIGHTING_MAX_CLOCK_SKEW = 5.minutes

//Every report restarts this, so one spot stays quiet until nobody reported it for this long.
private val SIGHTING_PUSH_COOLDOWN = 3.hours

// Extension functions on purpose: a property on LocationData would be stored and sent as a field.
private fun LocationData.isMobileRadar(): Boolean =
    this is LocationData.Radar && (radarMobile as? AttributeValue.BoolValue)?.value == true

/** A radar only has a last seen while it is mobile, which the fixed schema can not express. */
fun LocationData.withoutStaleLastSeen(): LocationData =
    if (this is LocationData.Radar && !isMobileRadar()) copy(radarLastSeen = null) else this

private fun LocationData.lastSeenMillis(): Long? {
    val value = when (this) {
        is LocationData.Police -> policeLastSeen
        is LocationData.Radar -> radarLastSeen.takeIf { isMobileRadar() }
        else -> null
    }
    return (value as? AttributeValue.LongValue)?.value
}

/**
 * What this save reports as newly seen, if anything. A last seen counts when it is at most an hour
 * old, so editing an entry with an old value stays quiet, and the one before it is at least
 * [SIGHTING_PUSH_COOLDOWN] old. [old] is null for a new entry.
 */
fun newSighting(old: MapEntry?, new: MapEntry, now: Instant): MapSightingType? {
    if (new.deleted) return null

    val earliest = (now - SIGHTING_MAX_AGE).toEpochMilliseconds()
    val latest = (now + SIGHTING_MAX_CLOCK_SKEW).toEpochMilliseconds()
    val cooldownStart = (now - SIGHTING_PUSH_COOLDOWN).toEpochMilliseconds()

    return new.locationData.firstNotNullOfOrNull { data ->
        val seen = data.lastSeenMillis() ?: return@firstNotNullOfOrNull null
        val before = old?.locationData.orEmpty()
            .filter { it::class == data::class }
            .mapNotNull { it.lastSeenMillis() }
            .maxOrNull()

        when {
            seen !in earliest..latest -> null
            before != null && before > cooldownStart -> null
            data is LocationData.Police -> MapSightingType.POLICE
            else -> MapSightingType.RADAR
        }
    }
}
