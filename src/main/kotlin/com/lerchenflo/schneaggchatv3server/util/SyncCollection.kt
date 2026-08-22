package com.lerchenflo.schneaggchatv3server.util

/**
 * Every collection that hands out incremental sync versions via [VersionCounterService].
 * Keeping this as a closed enum (rather than passing raw collection-name strings around)
 * makes the set of versioned collections typo-proof and gives a single place to look up
 * what participates in version-based sync.
 *
 * [key] is what is stored as the `_id` of the corresponding `counters` document - it is kept
 * stable independently of the Kotlin constant name in case a constant is ever renamed.
 */
enum class SyncCollection(val key: String) {
    MESSAGES("messages"),
    // Added when users/groups/events/map migrate to version-based sync:
    // USERS("users"), GROUPS("groups"), EVENTS("events"), MAP("mapentries")
}
