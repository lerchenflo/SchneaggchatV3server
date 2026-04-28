package com.lerchenflo.schneaggchatv3server.core

import com.google.gson.stream.MalformedJsonException
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.util.function.Consumer

@RestControllerAdvice
class GlobalExceptionHandler(
    private val loggingService: LoggingService
) {
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    //Exception handling for annotations (For example Registerrequest: Email)
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationError(e: MethodArgumentNotValidException): ResponseEntity<Map<String, Any>> {
        AppLogger.error("Validation Error happened: ${e.message}")
        val errors = e.bindingResult.allErrors.map {
            it.defaultMessage ?: "Invalid value"
        }
        //logError(e)
        return ResponseEntity
            .status(400)
            .body(mapOf("errors" to errors))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(e: IllegalArgumentException): ResponseEntity<String> {
        AppLogger.error("Illegal argument Error happened: ${e.message}")
        val error = e.message
        //logError(e)
        return ResponseEntity
            .status(400)
            .body(error)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(e: ResponseStatusException): ResponseEntity<String> {
        AppLogger.error("ResponseStatus Error happened: ${e.message}")
        val error = e.message

        // Log 500 errors with full stack trace
        if (e.statusCode.value() >= 500) {
            logger.error("Server error (${e.statusCode.value()}): ${e.message}", e)
            logError(e)
        }

        return ResponseEntity
            .status(e.statusCode)
            .body(error)
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFoundException(e: NoResourceFoundException): ResponseEntity<String> {
        AppLogger.error("NoResourceFound Error happened: ${e.message}")
        val resourcePath = e.resourcePath
        
        //logError(e)
        return ResponseEntity
            .status(404)
            .body("Resource not found: $resourcePath")
    }


    // Catch-all handler for any unhandled exceptions
    @ExceptionHandler(Exception::class)
    fun handleGeneralException(e: Exception): ResponseEntity<String> {

        //No stack trace printing for badcredentials (Someone used a wrong username)
        if (e !is BadCredentialsException){
            logger.error("Unhandled server error: ${e.javaClass.simpleName} - ${e.message}", e)
            AppLogger.error("Unhandled server error: ${e.javaClass.simpleName} - ${e.message}")

            logError(e)
        }


        return ResponseEntity
            .status(500)
            .body("An unexpected error occurred. Please try again later.")
    }

    private fun logError(e : Exception) {
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String

        loggingService.log(
            userId = if (requestingUserId != null) ObjectId(requestingUserId) else null ,
            logType = LogType.EXCEPTION_THROWN,
            message = e.message,
        )
    }

}