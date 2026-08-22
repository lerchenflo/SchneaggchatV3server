package com.lerchenflo.schneaggchatv3server.util

import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

/**
 * Hands out monotonically increasing version numbers per [SyncCollection], backed by an atomic
 * `$inc` on a single `counters` document (mirrors the `findAndModify` pattern already used for
 * refresh-token rotation in `AuthService`). Callers use these versions to stamp documents so
 * clients can sync with `version > since` instead of sending their entire known id/timestamp set.
 *
 * A version can be allocated (via [next]/[reserve]) slightly before the document carrying it is
 * actually persisted. A sync request landing in that window could otherwise read version N+1 and
 * permanently skip N once it lands. [withVersion]/[withVersions] track allocated-but-not-yet-committed
 * versions in memory so [safeWatermark] can report the highest version guaranteed to be fully
 * written - sync callers should always bound their query by it instead of [current].
 */
@Component
class VersionCounterService(
    private val mongoTemplate: MongoTemplate,
) {

    // Per-collection set of versions (or, for a reserved block, its lowest version) that have been
    // allocated but whose write has not finished yet.
    private val inFlight = ConcurrentHashMap<SyncCollection, ConcurrentSkipListSet<Long>>()

    private fun inFlightSetFor(collection: SyncCollection): ConcurrentSkipListSet<Long> =
        inFlight.computeIfAbsent(collection) { ConcurrentSkipListSet() }

    private fun incrementBy(collection: SyncCollection, amount: Long): Long {
        val query = Query(Criteria.where("_id").`is`(collection.key))
        val update = Update().inc("seq", amount)

        val result = mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true).upsert(true),
            VersionCounter::class.java,
        ) ?: throw IllegalStateException("Failed to increment version counter for '${collection.key}'")

        return result.seq
    }

    /** Atomically allocates and returns the next single version for [collection]. */
    fun next(collection: SyncCollection): Long = incrementBy(collection, 1)

    /** Atomically allocates a contiguous block of [count] versions for [collection]. */
    fun reserve(collection: SyncCollection, count: Int): LongRange {
        require(count > 0) { "count must be positive" }
        val last = incrementBy(collection, count.toLong())
        val first = last - count + 1
        return first..last
    }

    /** Current counter value for [collection] (0 if nothing has ever been allocated). */
    fun current(collection: SyncCollection): Long =
        mongoTemplate.findById(collection.key, VersionCounter::class.java)?.seq ?: 0L

    /**
     * Allocates one version, marks it in-flight for the duration of [block], then clears it -
     * regardless of whether [block] throws.
     */
    fun <T> withVersion(collection: SyncCollection, block: (Long) -> T): T {
        val version = next(collection)
        val set = inFlightSetFor(collection)
        set.add(version)
        try {
            return block(version)
        } finally {
            set.remove(version)
        }
    }

    /**
     * Allocates a contiguous block of [count] versions, marks the block in-flight (by its lowest
     * version - sufficient since the whole block clears together) for the duration of [block],
     * then clears it - regardless of whether [block] throws.
     */
    fun <T> withVersions(collection: SyncCollection, count: Int, block: (LongRange) -> T): T {
        val range = reserve(collection, count)
        val set = inFlightSetFor(collection)
        set.add(range.first)
        try {
            return block(range)
        } finally {
            set.remove(range.first)
        }
    }

    /**
     * Highest version safe to treat as fully committed: everything <= this value is guaranteed to
     * have finished writing, so bounding a sync query by it can never skip a version that is still
     * mid-write.
     *
     * NOTE: the in-flight tracking above is in-process only, which is safe today because this
     * server runs as a single instance (see the "WebSocket sessions are in-memory only" gotcha in
     * the architecture skill). Before this app is ever horizontally scaled, this must become a
     * shared watermark (e.g. Redis-backed) instead.
     */
    fun safeWatermark(collection: SyncCollection): Long {
        val lowestInFlight = inFlightSetFor(collection).firstOrNull()
        return if (lowestInFlight != null) lowestInFlight - 1 else current(collection)
    }
}
