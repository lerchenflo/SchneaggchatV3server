@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.monitoring.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * How often one endpoint answered with one status in one hour. Counts every request, not only the
 * failed ones in `app_logs`, so the dashboard shows which endpoints are called at all and how often.
 *
 * Written by `RequestCountService` with `$inc` upserts. [id] is
 * `"<hour epoch seconds>|<method>|<endpoint>|<status>"`, so the upsert matches on the id alone and
 * never on the [hour] sub-document.
 */
@TypeAlias("requestCount")
@Document("request_counts")
@CompoundIndexes(
    CompoundIndex(name = "hour_idx", def = "{'hour.epochSeconds': -1}"),
)
data class RequestCount(
    @Id val id: String,
    /** Start of the hour. */
    val hour: Instant,
    val method: String,
    /** Route pattern (`/groups/{id}`), see `RequestLogFilter`. */
    val endpoint: String,
    val status: Int,
    val count: Long,
)
