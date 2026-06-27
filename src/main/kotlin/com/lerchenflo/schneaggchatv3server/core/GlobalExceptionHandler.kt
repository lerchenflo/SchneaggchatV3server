package com.lerchenflo.schneaggchatv3server.core

import com.lerchenflo.schneaggchatv3server.core.security.ratelimit.ClientIpResolver
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import jakarta.servlet.http.HttpServletRequest
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler(
    private val loggingService: LoggingService,
    private val clientIpResolver: ClientIpResolver,
) {
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    //Exception handling for annotations (For example Registerrequest: Email)
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationError(e: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<Map<String, Any>> {
        val ip = clientIpResolver.resolve(request)
        AppLogger.error("Validation Error happened: ${e.message} | ip=$ip")
        val errors = e.bindingResult.allErrors.map {
            it.defaultMessage ?: "Invalid value"
        }
        //logError(e, ip)
        return ResponseEntity
            .status(400)
            .body(mapOf("errors" to errors))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(e: IllegalArgumentException, request: HttpServletRequest): ResponseEntity<String> {
        val ip = clientIpResolver.resolve(request)
        AppLogger.error("Illegal argument Error happened: ${e.message} | ip=$ip")
        val error = e.message
        //logError(e, ip)
        return ResponseEntity
            .status(400)
            .body(error)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(e: ResponseStatusException, request: HttpServletRequest): ResponseEntity<String> {
        val ip = clientIpResolver.resolve(request)
        AppLogger.error("ResponseStatus Error happened: ${e.message} | ip=$ip")
        val error = e.message

        // Log 500 errors with full stack trace
        if (e.statusCode.value() >= 500) {
            logger.error("Server error (${e.statusCode.value()}): ${e.message}", e)
            logError(e, ip)
        }

        return ResponseEntity
            .status(e.statusCode)
            .body(error)
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFoundException(e: NoResourceFoundException, request: HttpServletRequest): ResponseEntity<String> {
        val ip = clientIpResolver.resolve(request)
        AppLogger.error("NoResourceFound Error happened: ${e.message} | ip=$ip")
        val resourcePath = e.resourcePath

        //logError(e, ip)
        return ResponseEntity
            .status(404)
            .body("Resource not found: $resourcePath")
    }


    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(e: HttpMessageNotReadableException, request: HttpServletRequest): ResponseEntity<Map<String, String>> {
        val ip = clientIpResolver.resolve(request)
        AppLogger.error("HttpMessageNotReadableException Error happened: ${e.message} | ip=$ip")

        e.printStackTrace()

        return ResponseEntity
            .badRequest()
            .body(mapOf("error" to "Invalid request body: ${e.message}"))
    }

    // Catch-all handler for any unhandled exceptions
    @ExceptionHandler(Exception::class)
    fun handleGeneralException(e: Exception, request: HttpServletRequest): ResponseEntity<String> {
        val ip = clientIpResolver.resolve(request)

        //No stack trace printing for badcredentials (Someone used a wrong username)
        if (e !is BadCredentialsException){
            logger.error("Unhandled server error: ${e.javaClass.simpleName} - ${e.message}", e)
            AppLogger.error("Unhandled server error: ${e.javaClass.simpleName} - ${e.message} | ip=$ip")

            logError(e, ip)
        }


        return ResponseEntity
            .status(500)
            .body("An unexpected error occurred. Please try again later.")
    }

    private fun logError(e: Exception, ip: String? = null) {
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String

        loggingService.log(
            userId = if (requestingUserId != null) ObjectId(requestingUserId) else null,
            logType = LogType.EXCEPTION_THROWN,
            message = "${e.message}${if (ip != null) " | ip=$ip" else ""}",
        )
    }

}