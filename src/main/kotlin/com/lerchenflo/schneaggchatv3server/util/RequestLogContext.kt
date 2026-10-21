package com.lerchenflo.schneaggchatv3server.util

import org.bson.types.ObjectId
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Per-request data every log line of that request is stamped with. Opened and closed by
 * `RequestLogFilter`; [userId] is filled in by `JwtAuthFilter` once the token is validated, so it
 * is still known after Spring Security cleared its own context at the end of the chain.
 *
 * Thread-bound: work handed to coroutines / other threads logs without request context.
 */
class RequestLogContext(
    val requestId: String,
    val method: String,
    val path: String,
    /** Only used to tell anonymous sources apart when merging repeats - never stored. */
    val clientIp: String? = null,
) {
    @Volatile
    var userId: String? = null

    /** Tag of the failure that ended this request, set by the exception handler for the request log row. */
    @Volatile
    var failureTag: String? = null

    /** Short reason (exception message) for the request log row. */
    @Volatile
    var failureReason: String? = null

    companion object {
        private val holder = ThreadLocal<RequestLogContext?>()

        fun current(): RequestLogContext? = holder.get()

        fun open(context: RequestLogContext) = holder.set(context)

        fun close() = holder.remove()

        /** Fallback for lines logged outside a request filter pass but with a security context. */
        fun authenticatedUserId(): String? =
            (SecurityContextHolder.getContext().authentication?.principal as? String)
                ?.takeIf { ObjectId.isValid(it) }
    }
}
