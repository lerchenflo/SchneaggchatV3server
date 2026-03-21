package com.lerchenflo.schneaggchatv3server.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Lightweight console logger with colored, timestamped output.
 * Use this object anywhere instead of println().
 *
 * Colors:
 *   INFO    → Cyan
 *   SUCCESS → Green
 *   WARN    → Yellow
 *   ERROR   → Red
 *   DEBUG   → Magenta
 */
object AppLogger {

    private val formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss.SSS")

    // ANSI color codes
    private const val RESET   = "\u001B[0m"
    private const val RED     = "\u001B[31m"
    private const val GREEN   = "\u001B[32m"
    private const val YELLOW  = "\u001B[33m"
    private const val CYAN    = "\u001B[36m"
    private const val MAGENTA = "\u001B[35m"
    private const val GRAY    = "\u001B[90m"

    private fun timestamp(): String = LocalDateTime.now().format(formatter)

    private fun log(color: String, level: String, message: String) {
        val ts = "${GRAY}${timestamp()}${RESET}"
        val lvl = "${color}${level.padEnd(7)}${RESET}"
        println("$ts $lvl $message")
    }

    /** General information */
    fun info(message: String) = log(CYAN, "INFO", message)

    /** Successful operations */
    fun success(message: String) = log(GREEN, "SUCCESS", message)

    /** Warnings — non-critical issues */
    fun warn(message: String) = log(YELLOW, "WARN", message)

    /** Errors — failures that need attention */
    fun error(message: String) = log(RED, "ERROR", message)

    /** Debug — verbose info for development */
    fun debug(message: String) = log(MAGENTA, "DEBUG", message)
}
