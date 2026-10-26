@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.monitoring

import com.lerchenflo.schneaggchatv3server.monitoring.model.AppLog
import com.lerchenflo.schneaggchatv3server.monitoring.model.AppLogKind
import com.lerchenflo.schneaggchatv3server.monitoring.model.AppLogPage
import com.lerchenflo.schneaggchatv3server.monitoring.model.EndpointFailure
import com.lerchenflo.schneaggchatv3server.monitoring.model.ErrorTypeSummary
import com.lerchenflo.schneaggchatv3server.monitoring.model.ErrorTypesResponse
import com.lerchenflo.schneaggchatv3server.monitoring.model.LevelCounts
import com.lerchenflo.schneaggchatv3server.monitoring.model.MetricsSeriesResponse
import com.lerchenflo.schneaggchatv3server.monitoring.model.MonitoringOverview
import com.lerchenflo.schneaggchatv3server.monitoring.model.MonitoringRange
import com.lerchenflo.schneaggchatv3server.monitoring.model.ServerMetricsSnapshot
import com.lerchenflo.schneaggchatv3server.monitoring.model.ServerMetricsSnapshotResponse
import com.lerchenflo.schneaggchatv3server.monitoring.model.StatusCount
import com.lerchenflo.schneaggchatv3server.monitoring.model.TimeAxis
import com.lerchenflo.schneaggchatv3server.repository.AppLogRepository
import com.lerchenflo.schneaggchatv3server.repository.ServerMetricsRepository
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.util.AppLogLevel
import org.bson.Document
import org.bson.types.ObjectId
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service
import java.util.regex.Pattern
import kotlin.time.ExperimentalTime

/**
 * Read side of the admin monitoring dashboard. All bucketing happens in Mongo aggregations, so a
 * month of logs never has to be loaded into memory.
 */
@Service
class MonitoringService(
    private val mongoTemplate: MongoTemplate,
    private val appLogRepository: AppLogRepository,
    private val serverMetricsRepository: ServerMetricsRepository,
    private val appLogService: AppLogService,
    private val userLookupService: UserLookupService,
) {
    private companion object {
        const val LOGS = "app_logs"
        const val METRICS = "server_metrics"
        const val MAX_ERROR_TYPES = 300
        const val MAX_ENDPOINTS = 15

        /** kotlin.time.Instant is stored as `{epochSeconds, nanosecondsOfSecond}` - filter, sort and bucket on the seconds. */
        const val EPOCH = "timestamp.epochSeconds"

        /** A merged summary row stands for [AppLog.count] occurrences; rows from before the merge have no field. */
        val WEIGHT = Document("\$ifNull", listOf("\$count", 1))

        /** Averaged per chart column. */
        val GAUGES = listOf(
            "heapUsedMb", "heapMaxMb", "nonHeapUsedMb", "processCpuPercent", "systemCpuPercent",
            "threadCount", "socketConnections", "socketUsers", "mongoPingMs", "redisPingMs",
        )

        /** Summed per chart column. */
        val COUNTERS = listOf(
            "gcCount", "gcTimeMs", "fcmSent", "fcmFailed", "apnsSent", "apnsFailed", "emailsSent",
            "emailsFailed", "messagesSent", "newAccounts", "logins", "logsDropped",
        )
    }

    fun axisOf(range: MonitoringRange): TimeAxis {
        val now = System.currentTimeMillis()
        val start = (now - range.durationMs) / range.bucketMs * range.bucketMs
        val buckets = ((now - start) / range.bucketMs + 1).toInt()
        return TimeAxis(start = start, bucketMs = range.bucketMs, buckets = buckets)
    }

    fun overview(range: MonitoringRange): MonitoringOverview {
        val axis = axisOf(range)
        val previousStart = axis.start - range.durationMs

        val errorSeries = LongArray(axis.buckets)
        val warningSeries = LongArray(axis.buckets)
        val clientErrorSeries = LongArray(axis.buckets)
        val serverErrorSeries = LongArray(axis.buckets)

        aggregate(
            LOGS,
            match(previousStart, Document("level", Document("\$in", listOf("WARN", "ERROR")))),
            Document(
                "\$group", Document(
                    "_id", Document("kind", "\$kind").append("level", "\$level").append("bucket", bucketExpr(axis))
                ).append("count", Document("\$sum", WEIGHT))
            ),
        ).forEach { row ->
            val id = row.get("_id", Document::class.java)
            val bucket = (id["bucket"] as Number).toInt()
            if (bucket !in 0 until axis.buckets) return@forEach
            val count = (row["count"] as Number).toLong()
            val isError = id["level"] == "ERROR"
            when (id["kind"]) {
                "LOG" -> (if (isError) errorSeries else warningSeries)[bucket] += count
                "REQUEST" -> (if (isError) serverErrorSeries else clientErrorSeries)[bucket] += count
            }
        }

        val requestMatch = match(axis.start, Document("kind", "REQUEST"))
        val statusCodes = aggregate(
            LOGS, requestMatch,
            Document("\$group", Document("_id", "\$status").append("count", Document("\$sum", WEIGHT))),
            Document("\$sort", Document("count", -1)),
        ).mapNotNull { row ->
            val status = (row["_id"] as? Number)?.toInt() ?: return@mapNotNull null
            StatusCount(status, (row["count"] as Number).toLong())
        }

        // One row per method + endpoint + status
        val endpointStatusRows = aggregate(
            LOGS, requestMatch,
            Document(
                "\$group", Document("_id", Document("method", "\$method").append("endpoint", "\$endpoint").append("status", "\$status"))
                    .append("count", Document("\$sum", WEIGHT))
                    .append("lastSeen", Document("\$max", "\$$EPOCH"))
            ),
        )
        val methodOf = { row: Document -> row.get("_id", Document::class.java)["method"] as? String }
        val endpointOf = { row: Document -> (row.get("_id", Document::class.java)["endpoint"] as? String) ?: "?" }
        val statusOf = { row: Document -> (row.get("_id", Document::class.java)["status"] as? Number)?.toInt() ?: 0 }
        val countOf = { row: Document -> (row["count"] as Number).toLong() }
        val lastSeenOf = { row: Document -> (row["lastSeen"] as Number).toLong() * 1000 }

        val topEndpoints = endpointStatusRows.groupBy { row ->
            methodOf(row) to endpointOf(row)
        }.map { (key, rows) ->
            EndpointFailure(
                method = key.first,
                endpoint = key.second,
                count = rows.sumOf(countOf),
                lastSeen = rows.maxOf(lastSeenOf),
                statuses = rows.map { StatusCount(status = statusOf(it), count = countOf(it)) }.sortedByDescending { it.count },
            )
        }.sortedByDescending { it.count }.take(MAX_ENDPOINTS)

        // The same rows per status code, for the dashboard's status filter - an endpoint outside the
        // overall top list can still be the one with the most 401s
        val topEndpointsByStatus = endpointStatusRows.groupBy(statusOf).mapValues { (status, rows) ->
            rows.map { row ->
                EndpointFailure(
                    method = methodOf(row),
                    endpoint = endpointOf(row),
                    count = countOf(row),
                    lastSeen = lastSeenOf(row),
                    statuses = listOf(StatusCount(status = status, count = countOf(row))),
                )
            }.sortedByDescending { it.count }.take(MAX_ENDPOINTS)
        }

        return MonitoringOverview(
            axis = axis,
            current = levelCounts(axis.start, null),
            previous = levelCounts(previousStart, axis.start),
            errorSeries = errorSeries.toList(),
            warningSeries = warningSeries.toList(),
            clientErrorSeries = clientErrorSeries.toList(),
            serverErrorSeries = serverErrorSeries.toList(),
            statusCodes = statusCodes,
            topEndpoints = topEndpoints,
            topEndpointsByStatus = topEndpointsByStatus,
            latestMetrics = serverMetricsRepository.findFirstByOrderByTimestampDesc()?.toResponse(),
        )
    }

    /**
     * Every distinct tag of WARN/ERROR log lines in the range, with its own column series.
     * [levels] narrows to errors or warnings only.
     */
    fun errorTypes(range: MonitoringRange, levels: List<AppLogLevel>): ErrorTypesResponse {
        val axis = axisOf(range)
        val filter = Document("kind", "LOG").append("level", Document("\$in", levels.map { it.name }))
        val tagExpr = Document("\$ifNull", listOf("\$tag", "(ohne Tag)"))

        val summaries = aggregate(
            LOGS,
            match(axis.start, filter),
            Document("\$sort", Document(EPOCH, 1)),
            Document(
                "\$group", Document("_id", tagExpr)
                    .append("count", Document("\$sum", WEIGHT))
                    .append("errors", Document("\$sum", Document("\$cond", listOf(Document("\$eq", listOf("\$level", "ERROR")), WEIGHT, 0))))
                    .append("firstSeen", Document("\$min", "\$$EPOCH"))
                    .append("lastSeen", Document("\$max", "\$$EPOCH"))
                    .append("users", Document("\$addToSet", "\$userId"))
                    .append("lastMessage", Document("\$last", "\$message"))
            ),
            Document("\$sort", Document("count", -1)),
            Document("\$limit", MAX_ERROR_TYPES),
        )

        val series = mutableMapOf<String, LongArray>()
        aggregate(
            LOGS,
            match(axis.start, filter),
            Document(
                "\$group", Document("_id", Document("tag", tagExpr).append("bucket", bucketExpr(axis)))
                    .append("count", Document("\$sum", WEIGHT))
            ),
        ).forEach { row ->
            val id = row.get("_id", Document::class.java)
            val bucket = (id["bucket"] as Number).toInt()
            if (bucket !in 0 until axis.buckets) return@forEach
            series.getOrPut(id["tag"] as String) { LongArray(axis.buckets) }[bucket] += (row["count"] as Number).toLong()
        }

        val types = summaries.map { row ->
            val tag = row["_id"] as String
            ErrorTypeSummary(
                tag = tag,
                level = if ((row["errors"] as Number).toLong() > 0) AppLogLevel.ERROR else AppLogLevel.WARN,
                count = (row["count"] as Number).toLong(),
                firstSeen = (row["firstSeen"] as Number).toLong() * 1000,
                lastSeen = (row["lastSeen"] as Number).toLong() * 1000,
                // addToSet keeps one null entry for lines without a user - that is not a user.
                affectedUsers = (row["users"] as List<*>).count { it != null }.toLong(),
                lastMessage = (row["lastMessage"] as? String).orEmpty().take(300),
                series = (series[tag] ?: LongArray(axis.buckets)).toList(),
            )
        }
        return ErrorTypesResponse(axis = axis, types = types)
    }

    fun metricsSeries(range: MonitoringRange): MetricsSeriesResponse {
        val axis = axisOf(range)
        val group = Document("_id", bucketExpr(axis))
        GAUGES.forEach { group.append(it, Document("\$avg", "\$$it")) }
        COUNTERS.forEach { group.append(it, Document("\$sum", "\$$it")) }
        group.append("activeDevices", Document("\$last", "\$activeDevices"))

        val gauges = GAUGES.associateWith { arrayOfNulls<Double>(axis.buckets) }
        val counters = COUNTERS.associateWith { arrayOfNulls<Long>(axis.buckets) }
        val devices = mutableMapOf<String, Array<Long?>>()

        aggregate(
            METRICS,
            match(axis.start, Document()),
            Document("\$sort", Document(EPOCH, 1)),
            Document("\$group", group),
        ).forEach { row ->
            val bucket = (row["_id"] as Number).toInt()
            if (bucket !in 0 until axis.buckets) return@forEach
            GAUGES.forEach { gauges.getValue(it)[bucket] = (row[it] as? Number)?.toDouble() }
            COUNTERS.forEach { counters.getValue(it)[bucket] = (row[it] as? Number)?.toLong() }
            (row["activeDevices"] as? Document)?.forEach { (type, count) ->
                devices.getOrPut(type) { arrayOfNulls(axis.buckets) }[bucket] = (count as? Number)?.toLong()
            }
        }

        return MetricsSeriesResponse(
            axis = axis,
            gauges = gauges.mapValues { it.value.toList() },
            counters = counters.mapValues { it.value.toList() },
            activeDevices = devices.mapValues { it.value.toList() },
        )
    }

    /**
     * Log explorer. [user] is a user id or a username; [query] is a case-insensitive substring of
     * the message, tag or path.
     */
    fun logs(
        range: MonitoringRange,
        kind: AppLogKind?,
        levels: List<AppLogLevel>,
        tag: String?,
        user: String?,
        query: String?,
        page: Int,
        pageSize: Int,
    ): AppLogPage {
        val criteria = Criteria.where(EPOCH).gte(axisOf(range).start / 1000)
        if (kind != null) criteria.and("kind").`is`(kind)
        if (levels.isNotEmpty()) criteria.and("level").`in`(levels)
        if (!tag.isNullOrBlank()) criteria.and("tag").`is`(tag)
        if (!user.isNullOrBlank()) {
            val userId = if (ObjectId.isValid(user)) ObjectId(user) else userLookupService.findByUsername(user)?.id
                ?: return AppLogPage(emptyList(), false)
            criteria.and("userId").`is`(userId)
        }
        if (!query.isNullOrBlank()) {
            val pattern = Pattern.compile(Pattern.quote(query.take(200)), Pattern.CASE_INSENSITIVE)
            criteria.orOperator(
                Criteria.where("message").regex(pattern),
                Criteria.where("tag").regex(pattern),
                Criteria.where("path").regex(pattern),
            )
        }

        // One extra row tells whether another page exists, without a count over the whole range.
        val rows = mongoTemplate.find(
            Query(criteria)
                .with(Sort.by(Sort.Direction.DESC, EPOCH))
                .skip(page.toLong() * pageSize)
                .limit(pageSize + 1),
            AppLog::class.java,
        )
        return AppLogPage(
            entries = appLogService.toResponses(rows.take(pageSize)),
            moreEntries = rows.size > pageSize,
        )
    }

    /** Every line of one request in order - the request row plus everything logged while it ran. */
    fun trace(requestId: String) =
        appLogService.toResponses(appLogRepository.findByRequestIdOrderByTimestampAsc(requestId))

    /** WARN/ERROR line counts plus failed requests in `[from, to)`. */
    private fun levelCounts(from: Long, to: Long?): LevelCounts {
        val timestamp = Document("\$gte", from / 1000)
        if (to != null) timestamp.append("\$lt", to / 1000)

        val facet = aggregate(
            LOGS,
            Document("\$match", Document(EPOCH, timestamp).append("level", Document("\$in", listOf("WARN", "ERROR")))),
            Document(
                "\$facet", Document(
                    "byKindLevel", listOf(
                        Document("\$group", Document("_id", Document("kind", "\$kind").append("level", "\$level")).append("count", Document("\$sum", WEIGHT)))
                    )
                ).append(
                    "users", listOf(
                        Document("\$match", Document("userId", Document("\$ne", null))),
                        Document("\$group", Document("_id", "\$userId")),
                        Document("\$count", "count"),
                    )
                )
            ),
        ).firstOrNull()

        val counts = (facet?.getList("byKindLevel", Document::class.java) ?: emptyList()).associate { row ->
            val id = row.get("_id", Document::class.java)
            "${id["kind"]}_${id["level"]}" to (row["count"] as Number).toLong()
        }
        val users = facet?.getList("users", Document::class.java)?.firstOrNull()?.let { (it["count"] as Number).toLong() } ?: 0

        return LevelCounts(
            errors = counts["LOG_ERROR"] ?: 0,
            warnings = counts["LOG_WARN"] ?: 0,
            failedRequests = (counts["REQUEST_WARN"] ?: 0) + (counts["REQUEST_ERROR"] ?: 0),
            serverErrors = counts["REQUEST_ERROR"] ?: 0,
            affectedUsers = users,
        )
    }

    private fun match(from: Long, filter: Document) =
        Document("\$match", Document(filter).append(EPOCH, Document("\$gte", from / 1000)))

    /** Column index of a row's timestamp on [axis]. */
    private fun bucketExpr(axis: TimeAxis) = Document(
        "\$floor", Document(
            "\$divide", listOf(
                Document("\$subtract", listOf(Document("\$multiply", listOf("\$$EPOCH", 1000L)), axis.start)),
                axis.bucketMs,
            )
        )
    )

    private fun aggregate(collection: String, vararg stages: Document): List<Document> =
        mongoTemplate.getCollection(collection).aggregate(stages.toList()).allowDiskUse(true).into(mutableListOf())

    private fun ServerMetricsSnapshot.toResponse() = ServerMetricsSnapshotResponse(
        timestamp = timestamp.toEpochMilliseconds(),
        heapUsedMb = heapUsedMb,
        heapMaxMb = heapMaxMb,
        nonHeapUsedMb = nonHeapUsedMb,
        processCpuPercent = processCpuPercent,
        systemCpuPercent = systemCpuPercent,
        threadCount = threadCount,
        uptimeSeconds = uptimeSeconds,
        socketConnections = socketConnections,
        socketUsers = socketUsers,
        activeDevices = activeDevices,
        mongoPingMs = mongoPingMs,
        redisPingMs = redisPingMs,
    )
}
