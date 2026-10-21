package com.lerchenflo.schneaggchatv3server.util

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Thrown by [requireOrLog]. Still an [IllegalArgumentException] (-> 400), but the failure was
 * already logged with its [tag], so the exception handler only forwards the tag to the request log
 * row instead of logging it a second time.
 */
class TaggedRequireException(val tag: String, message: String) : IllegalArgumentException(message)

/**
 * Like [require], but logs a WARN line tagged with [tag] (a unique call-site id such as
 * `join_event_no_access`, searchable in the admin monitoring dashboard) before throwing.
 *
 * [lazyDebugMessage] is only evaluated on failure - safe to do DB lookups in it
 * (e.g. resolving usernames / group names).
 * [lazyMessage] is the client-facing text; it lands in the 400 response body via
 * [com.lerchenflo.schneaggchatv3server.core.GlobalExceptionHandler].
 */
@OptIn(ExperimentalContracts::class)
inline fun requireOrLog(tag: String, value: Boolean, lazyDebugMessage: () -> String, lazyMessage: () -> Any) {
    contract { returns() implies value }
    if (!value) {
        AppLogger.warn(lazyDebugMessage(), tag = tag)
        throw TaggedRequireException(tag, lazyMessage().toString())
    }
}
