package com.lerchenflo.schneaggchatv3server.util

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Like [require], but emits an [AppLogger.debug] line with extra server-side context
 * before throwing.
 *
 * [lazyDebugMessage] is only evaluated on failure - safe to do DB lookups in it
 * (e.g. resolving usernames / group names).
 * [lazyMessage] is the client-facing text; it lands in the 400 response body via
 * [com.lerchenflo.schneaggchatv3server.core.GlobalExceptionHandler].
 */
@OptIn(ExperimentalContracts::class)
inline fun requireOrLog(value: Boolean, lazyDebugMessage: () -> String, lazyMessage: () -> Any) {
    contract { returns() implies value }
    if (!value) {
        AppLogger.debug(lazyDebugMessage())
        throw IllegalArgumentException(lazyMessage().toString())
    }
}
