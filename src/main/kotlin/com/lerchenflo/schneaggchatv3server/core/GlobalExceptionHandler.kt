package com.lerchenflo.schneaggchatv3server.core

import com.lerchenflo.schneaggchatv3server.core.security.ratelimit.ClientIpResolver
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import jakarta.servlet.http.HttpServletRequest
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler(
    private val loggingService: LoggingService,
    private val clientIpResolver: ClientIpResolver,
) {

    /** AppLogger adds the requesting user itself. Pass [error] to print the stack trace. */
    private fun logRequestError(message: String, ip: String?, error: Throwable? = null) {
        AppLogger.error(if (ip != null) "$message | ip=$ip" else message, error = error)
    }

    //Exception handling for annotations (For example Registerrequest: Email)
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationError(e: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<Map<String, Any>> {
        val ip = clientIpResolver.resolve(request)
        val errors = e.bindingResult.allErrors.map {
            it.defaultMessage ?: "Invalid value"
        }
        logRequestError("Validation Error happened: ${errors.joinToString(", ")}", ip)
        //logError(e, ip)
        return ResponseEntity
            .status(400)
            .body(mapOf("errors" to errors))
    }

    //Require exceptions etc
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(e: IllegalArgumentException, request: HttpServletRequest): ResponseEntity<String> {
        val ip = clientIpResolver.resolve(request)
        logRequestError("Illegal argument Error happened: ${e.message}", ip)
        val error = e.message
        //logError(e, ip)
        return ResponseEntity
            .status(400)
            .body(error)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(e: ResponseStatusException, request: HttpServletRequest): ResponseEntity<String> {
        val ip = clientIpResolver.resolve(request)
        val error = e.message

        // Log 500 errors with full stack trace
        if (e.statusCode.value() >= 500) {
            logRequestError("Server error (${e.statusCode.value()}): ${e.message}", ip, error = e)
            logError(e, request, ip)
        } else {
            logRequestError("ResponseStatus Error happened: ${e.message}", ip)
        }

        return ResponseEntity
            .status(e.statusCode)
            .body(error)
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFoundException(e: NoResourceFoundException, request: HttpServletRequest): ResponseEntity<String> {
        val ip = clientIpResolver.resolve(request)
        logRequestError("NoResourceFound Error happened: ${e.message}", ip)
        val resourcePath = e.resourcePath

        //logError(e, ip)
        return ResponseEntity
            .status(404)
            .body("Resource not found: $resourcePath")
    }


    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(e: HttpRequestMethodNotSupportedException, request: HttpServletRequest): ResponseEntity<String> {
        val ip = clientIpResolver.resolve(request)
        logRequestError("Wrong request method: ${e.method} not supported for endpoint ${request.requestURI}", ip)

        return ResponseEntity
            .status(HttpStatus.METHOD_NOT_ALLOWED)
            .body("Request method '${e.method}' is not supported for this endpoint")
    }

    /**
     * Malformed or missing request body (bad JSON, wrong types, empty body). A client-side bug, not a
     * server error: one concise warn line with the root cause, no stack trace and no error-log row -
     * a misbehaving client used to flood the logs with a full trace per request.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(e: HttpMessageNotReadableException, request: HttpServletRequest): ResponseEntity<Map<String, String>> {
        val ip = clientIpResolver.resolve(request)
        val cause = e.mostSpecificCause.message?.lineSequence()?.firstOrNull() ?: "unreadable body"
        AppLogger.warn("Unreadable request body on ${request.method} ${request.requestURI} (ip=$ip): $cause")

        return ResponseEntity
            .badRequest()
            .body(mapOf("error" to "Invalid request body: $cause"))
    }

    /**
     * A client that went away mid-stream (closed admin panel tab, dropped connection). The container
     * reports the broken pipe as an async error dispatch, which would otherwise land in the catch-all
     * below and be logged - and stored - as a server error. Nothing can be written to the response
     * either, so the handler returns void.
     */
    @ExceptionHandler(AsyncRequestNotUsableException::class)
    fun handleDisconnectedClient(e: AsyncRequestNotUsableException) {
        // Nothing to log: a client going away is normal
    }

    /**
     * Wrong username or password on login. Has to be a 401: the catch-all below answers 500, which
     * the app shows as a server problem instead of "invalid credentials". Same answer for unknown
     * user and wrong password, so accounts can't be enumerated. AuthService already logs the attempt.
     */
    @ExceptionHandler(BadCredentialsException::class)
    fun handleBadCredentials(e: BadCredentialsException): ResponseEntity<String> {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body("Invalid credentials")
    }

    // Catch-all handler for any unhandled exceptions
    @ExceptionHandler(Exception::class)
    fun handleGeneralException(e: Exception, request: HttpServletRequest): ResponseEntity<String> {
        val ip = clientIpResolver.resolve(request)

        //No stack trace printing for badcredentials (Someone used a wrong username)
        if (e !is BadCredentialsException){
            logRequestError("Unhandled server error: ${e.javaClass.simpleName} - ${e.message}", ip, error = e)

            logError(e, request, ip)
        }


        return ResponseEntity
            .status(500)
            .body("An unexpected error occurred. Please try again later.")
    }

    private fun logError(e: Exception, request: HttpServletRequest, ip: String? = null) {
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String

        // e.message is frequently null (many exceptions here carry no message), which used to leave
        // the admin log viewer showing literal "null" rows. Exception class + request path make a
        // message-less row still identifiable.
        loggingService.log(
            userId = if (requestingUserId != null) ObjectId(requestingUserId) else null,
            logType = LogType.EXCEPTION_THROWN,
            message = "${e.javaClass.simpleName}: ${e.message ?: "no message"} | path=${request.requestURI}${if (ip != null) " | ip=$ip" else ""}",
        )
    }

}