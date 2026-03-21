package com.lerchenflo.schneaggchatv3server.util

import org.springframework.dao.OptimisticLockingFailureException
import kotlin.math.min

/**
 * Executes an operation with optimistic locking retry logic
 * @param maxRetries Maximum number of retry attempts (default: 10)
 * @param operation Lambda that receives retry count and returns result
 * @return Result of the operation
 * @throws OptimisticLockingFailureException if max retries exceeded
 */
inline fun <T> withOptimisticRetry(
    maxRetries: Int = 10,
    operation: (retryCount: Int) -> T
): T {
    var retryCount = 0

    while (true) {
        try {
            return operation(retryCount)
        } catch (e: OptimisticLockingFailureException) {

            AppLogger.warn("Optimistic Locking exception caught: ${e.message}")

            retryCount++
            if (retryCount > maxRetries) {
                throw OptimisticLockingFailureException(
                    "Failed after $maxRetries retries due to optimistic locking conflicts", e
                )
            }
            // Optional: Add exponential backoff
            Thread.sleep(min(100L * retryCount, 1000L))
        }
    }
}