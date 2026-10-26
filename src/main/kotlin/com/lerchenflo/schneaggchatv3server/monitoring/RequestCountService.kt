@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.monitoring

import com.lerchenflo.schneaggchatv3server.monitoring.model.EndpointUsage
import com.lerchenflo.schneaggchatv3server.monitoring.model.EndpointUsageResponse
import com.lerchenflo.schneaggchatv3server.monitoring.model.MonitoringRange
import com.lerchenflo.schneaggchatv3server.monitoring.model.RequestCount
import com.lerchenflo.schneaggchatv3server.monitoring.model.StatusCount
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import jakarta.annotation.PreDestroy
import org.bson.Document
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Counts every HTTP request per hour, method, endpoint and status for the dashboard's endpoint
 * table. Requests only bump an in-memory counter; once a minute the counts go into
 * `request_counts` as `$inc` upserts, so a request never waits for Mongo.
 *
 * Only route patterns are counted (`/groups/{id}`, not every id), so this stays at a few thousand
 * rows per day, kept [RETENTION].
 */
@Service
class RequestCountService(
    private val mongoTemplate: MongoTemplate,
) {
    companion object {
        val RETENTION = 90.days

        /** Unmatched paths that failed (bot probes, typos) share one row instead of one per path. */
        const val UNMATCHED_ENDPOINT = "(unmatched)"

        /** Shared row once [MAX_KEYS] is hit - something is making up endpoint names. */
        const val OVERFLOW_ENDPOINT = "(overflow)"

        /** Any other method name (the client picks it freely) counts as this, so it can't add rows. */
        const val OTHER_METHOD = "OTHER"

        private val KNOWN_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

        private const val COLLECTION = "request_counts"
        private const val MAX_KEYS = 5_000
        private const val HOUR_SECONDS = 3_600L
    }

    private data class Key(val hourEpochSeconds: Long, val method: String, val endpoint: String, val status: Int) {
        val id get() = "$hourEpochSeconds|$method|$endpoint|$status"
    }

    private val counts = ConcurrentHashMap<Key, AtomicLong>()

    private fun currentHour(): Long = Clock.System.now().epochSeconds / HOUR_SECONDS * HOUR_SECONDS

    fun record(method: String, endpoint: String, status: Int) {
        var key = Key(currentHour(), method.takeIf { it in KNOWN_METHODS } ?: OTHER_METHOD, endpoint, status)
        if (counts.size >= MAX_KEYS && !counts.containsKey(key)) key = key.copy(endpoint = OVERFLOW_ENDPOINT)
        counts.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
    }

    @Scheduled(fixedDelay = 60_000)
    @Synchronized
    fun flush() {
        val rows = counts.mapNotNull { (key, counter) -> counter.getAndSet(0).takeIf { it > 0 }?.let { key to it } }

        // A past hour gets no new requests, so its emptied counters can go
        val hour = currentHour()
        counts.entries.removeIf { (key, counter) -> key.hourEpochSeconds < hour && counter.get() == 0L }

        if (rows.isEmpty()) return
        try {
            val bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, RequestCount::class.java)
            rows.forEach { (key, count) ->
                bulk.upsert(
                    Query(Criteria.where("_id").`is`(key.id)),
                    Update()
                        .setOnInsert("hour", Instant.fromEpochSeconds(key.hourEpochSeconds))
                        .setOnInsert("method", key.method)
                        .setOnInsert("endpoint", key.endpoint)
                        .setOnInsert("status", key.status)
                        .inc("count", count),
                )
            }
            bulk.execute()
        } catch (e: Exception) {
            // Keep the counts for the next flush
            rows.forEach { (key, count) -> counts.computeIfAbsent(key) { AtomicLong() }.addAndGet(count) }
            AppLogger.warn("Could not store ${rows.size} request counts: ${e.message}", tag = "flush_request_counts_failed")
        }
    }

    /** Last chance to store the counts of the running minute when the server shuts down. */
    @PreDestroy
    fun flushOnShutdown() = flush()

    fun deleteExpired() {
        val before = (Clock.System.now() - RETENTION).epochSeconds
        mongoTemplate.remove(Query(Criteria.where("hour.epochSeconds").lt(before)), COLLECTION)
    }

    /** Every endpoint called in [range], most called first. Counts are per hour, so the range starts at a full hour. */
    fun usage(range: MonitoringRange): EndpointUsageResponse {
        val since = (Clock.System.now().toEpochMilliseconds() - range.durationMs) / 1000 / HOUR_SECONDS * HOUR_SECONDS

        val rows = mongoTemplate.getCollection(COLLECTION).aggregate(
            listOf(
                Document("\$match", Document("hour.epochSeconds", Document("\$gte", since))),
                Document(
                    "\$group", Document("_id", Document("method", "\$method").append("endpoint", "\$endpoint").append("status", "\$status"))
                        .append("count", Document("\$sum", "\$count"))
                        .append("lastHour", Document("\$max", "\$hour.epochSeconds"))
                ),
            )
        ).toList()

        val endpoints = rows.groupBy { row ->
            val id = row.get("_id", Document::class.java)
            (id["method"] as? String ?: "?") to (id["endpoint"] as? String ?: "?")
        }.map { (key, group) ->
            val statuses = group.map {
                StatusCount(
                    status = (it.get("_id", Document::class.java)["status"] as? Number)?.toInt() ?: 0,
                    count = (it["count"] as Number).toLong(),
                )
            }.sortedByDescending { it.count }
            EndpointUsage(
                method = key.first,
                endpoint = key.second,
                count = statuses.sumOf { it.count },
                failed = statuses.filter { it.status >= 400 }.sumOf { it.count },
                statuses = statuses,
                lastHour = group.maxOf { (it["lastHour"] as Number).toLong() } * 1000,
            )
        }.sortedByDescending { it.count }

        return EndpointUsageResponse(since = since * 1000, endpoints = endpoints)
    }
}
