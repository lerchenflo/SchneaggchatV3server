package com.lerchenflo.schneaggchatv3server.tools

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/*
 * Rate-limit tester for the auth endpoints.
 *
 * Run from IntelliJ: click the green ▶ next to `main` below.
 * Tweak the constants in [Config] to point at prod / change load.
 *
 * Uses only the JDK (java.net.http) so it does not pull in the Spring context
 * and starts in milliseconds.
 */

private object Config {
    // Override via env vars if you want, otherwise edit here:
    val BASE_URL: String = System.getenv("RATE_LIMIT_BASE_URL")?.trimEnd('/')
        ?: "http://localhost:8083"
    val TOTAL_REQUESTS: Int = System.getenv("RATE_LIMIT_TOTAL")?.toIntOrNull() ?: 100
    val CONCURRENCY: Int = System.getenv("RATE_LIMIT_CONCURRENCY")?.toIntOrNull() ?: 20
    val PER_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
}

private data class Endpoint(
    val name: String,
    val method: String,
    val pathTemplate: String, // may contain a single %d for the request index
    val contentType: String?,
    val bodyFactory: (Int) -> String?,
)

private val endpoints = listOf(
    Endpoint(
        name = "POST /auth/login (bad creds)",
        method = "POST",
        pathTemplate = "/auth/login",
        contentType = "application/json",
        bodyFactory = { i -> """{"username":"ratelimit_user_$i","password":"WrongPass1!"}""" },
    ),
    Endpoint(
        name = "POST /auth/refresh (garbage token)",
        method = "POST",
        pathTemplate = "/auth/refresh",
        contentType = "application/json",
        bodyFactory = { i -> """{"refreshToken":"not-a-real-token-$i"}""" },
    ),
    Endpoint(
        name = "POST /auth/send_reset_email",
        method = "POST",
        pathTemplate = "/auth/send_reset_email?email=ratelimit_%d@example.com",
        contentType = null,
        bodyFactory = { _ -> null },
    ),
    Endpoint(
        name = "GET /auth/verify_email (bad token)",
        method = "GET",
        pathTemplate = "/auth/verify_email?token=invalidtoken%d",
        contentType = null,
        bodyFactory = { _ -> null },
    ),
)

private val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .version(HttpClient.Version.HTTP_1_1)
    .build()

private fun runTest(endpoint: Endpoint) {
    println("--- ${endpoint.name} ---")

    val statusCounts = ConcurrentHashMap<Int, AtomicInteger>()
    val errorCount = AtomicInteger(0)
    val totalLatencyNanos = AtomicLong(0)
    val maxLatencyNanos = AtomicLong(0)
    val firstRateLimitAt = AtomicInteger(-1)
    val sequence = AtomicInteger(0)

    val executor = Executors.newFixedThreadPool(Config.CONCURRENCY)
    val started = System.nanoTime()

    repeat(Config.TOTAL_REQUESTS) { idx ->
        executor.submit {
            val seq = sequence.incrementAndGet()
            try {
                val resolvedPath = if (endpoint.pathTemplate.contains("%d")) {
                    endpoint.pathTemplate.format(idx)
                } else {
                    endpoint.pathTemplate
                }

                val builder = HttpRequest.newBuilder()
                    .uri(URI.create("${Config.BASE_URL}$resolvedPath"))
                    .timeout(Config.PER_REQUEST_TIMEOUT)
                    .header("User-Agent", "SchneaggchatRateLimitTester/1.0")

                val body = endpoint.bodyFactory(idx)
                val publisher = if (body != null) {
                    HttpRequest.BodyPublishers.ofString(body)
                } else {
                    HttpRequest.BodyPublishers.noBody()
                }

                when (endpoint.method) {
                    "POST" -> builder.POST(publisher)
                    "GET" -> builder.GET()
                    "PUT" -> builder.PUT(publisher)
                    "DELETE" -> builder.DELETE()
                    else -> error("Unsupported method ${endpoint.method}")
                }
                endpoint.contentType?.let { builder.header("Content-Type", it) }

                val reqStart = System.nanoTime()
                val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding())
                val elapsed = System.nanoTime() - reqStart

                totalLatencyNanos.addAndGet(elapsed)
                maxLatencyNanos.updateAndGet { current -> if (elapsed > current) elapsed else current }
                statusCounts.computeIfAbsent(response.statusCode()) { AtomicInteger(0) }.incrementAndGet()
                if (response.statusCode() == 429) {
                    firstRateLimitAt.compareAndSet(-1, seq)
                }
            } catch (t: Throwable) {
                errorCount.incrementAndGet()
            }
        }
    }

    executor.shutdown()
    if (!executor.awaitTermination(2, TimeUnit.MINUTES)) {
        println("WARN: executor did not finish within 2 minutes; forcing shutdown")
        executor.shutdownNow()
    }

    val wallMs = (System.nanoTime() - started) / 1_000_000.0
    val completed = statusCounts.values.sumOf { it.get() }
    val avgMs = if (completed > 0) totalLatencyNanos.get() / 1_000_000.0 / completed else 0.0
    val maxMs = maxLatencyNanos.get() / 1_000_000.0
    val rps = if (wallMs > 0) Config.TOTAL_REQUESTS / (wallMs / 1000.0) else 0.0

    println("  wall time:    %.0f ms (~%.1f req/s)".format(wallMs, rps))
    println("  completed:    $completed / ${Config.TOTAL_REQUESTS}   errors: ${errorCount.get()}")
    println("  latency:      avg=%.1f ms  max=%.1f ms".format(avgMs, maxMs))
    println("  status codes:")
    statusCounts.entries
        .sortedBy { it.key }
        .forEach { (code, count) ->
            val tag = when (code) {
                429 -> " <-- rate limited"
                in 200..299 -> " ok"
                in 400..499 -> " client error"
                in 500..599 -> " server error"
                else -> ""
            }
            println("    $code: ${count.get()}$tag")
        }
    val firstRl = firstRateLimitAt.get()
    if (firstRl > 0) {
        println("  first 429 at request #$firstRl")
    } else {
        println("  no 429 seen — server did NOT rate-limit this endpoint at this load")
    }
    println()
}

fun main() {
    println("=== Schneaggchat rate-limit tester ===")
    println("Target:        ${Config.BASE_URL}")
    println("Requests/test: ${Config.TOTAL_REQUESTS}")
    println("Concurrency:   ${Config.CONCURRENCY}")
    println()

    endpoints.forEach { runTest(it) }

    println("=== done ===")
}
