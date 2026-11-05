package com.lerchenflo.schneaggchatv3server.core

import com.lerchenflo.schneaggchatv3server.core.security.ratelimit.ClientIpResolver
import com.lerchenflo.schneaggchatv3server.util.AppLogLevel
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.RequestLogContext
import com.lerchenflo.schneaggchatv3server.util.TaggedRequireException
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
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.ServletRequestBindingException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler(
    private val loggingService: LoggingService,
    private val clientIpResolver: ClientIpResolver,
) {

    /**
     * Console + monitoring store; AppLogger adds the requesting user itself. Also hands [tag] and the
     * message to the request's log context, so the failed-request row written by RequestLogFilter
     * says why the request failed. Pass [error] to print and store the stack trace.
     */
    private fun logRequestError(
        message: String,
        ip: String?,
        tag: String,
        level: AppLogLevel = AppLogLevel.WARN,
        error: Throwable? = null,
    ) {
        markRequestFailure(tag, message)

        val line = if (ip != null) "$message | ip=$ip" else message
        if (level == AppLogLevel.ERROR) {
            AppLogger.error(line, tag = tag, error = error)
        } else {
            AppLogger.warn(line, tag = tag, error = error)
        }
    }

    private fun markRequestFailure(tag: String, reason: String?) {
        RequestLogContext.current()?.let {
            it.failureTag = tag
            it.failureReason = reason?.take(500)
        }
    }

    //Exception handling for annotations (For example Registerrequest: Email)
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationError(e: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<Map<String, Any>> {
        val ip = clientIpResolver.resolve(request)
        val errors = e.bindingResult.allErrors.map {
            it.defaultMessage ?: "Invalid value"
        }
        logRequestError("Validation Error happened: ${errors.joinToString(", ")}", ip, AppLogger.autoTag(e))
        //logError(e, ip)
        return ResponseEntity
            .status(400)
            .body(mapOf("errors" to errors))
    }

    //Require exceptions etc
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(e: IllegalArgumentException, request: HttpServletRequest): ResponseEntity<String> {
        if (e is TaggedRequireException) {
            // requireOrLog already logged it with its call-site tag
            markRequestFailure(e.tag, e.message)
        } else {
            val ip = clientIpResolver.resolve(request)
            logRequestError("Illegal argument Error happened: ${e.message}", ip, AppLogger.autoTag(e))
        }
        val error = e.message
        //logError(e, ip)
        return ResponseEntity
            .status(400)
            .body(error)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(e: ResponseStatusException, request: HttpServletRequest): ResponseEntity<String> {
        val ip = clientIpResolver.resolve(request)
        val serverError = e.statusCode.value() >= 500
        // 500s with full stack trace
        logRequestError(
            if (serverError) "Server error (${e.statusCode.value()}): ${e.message}" else "ResponseStatus Error happened: ${e.message}",
            ip,
            AppLogger.autoTag(e),
            level = if (serverError) AppLogLevel.ERROR else AppLogLevel.WARN,
            error = if (serverError) e else null,
        )
        // Only the reason, like the require() messages; e.message adds a "400 BAD_REQUEST" prefix
        val error = e.reason ?: e.message

        if (serverError) {
            logError(e, request, ip)
        }

        return ResponseEntity
            .status(e.statusCode)
            .body(error)
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFoundException(e: NoResourceFoundException, request: HttpServletRequest): ResponseEntity<String> {
        val ip = clientIpResolver.resolve(request)
        logRequestError("NoResourceFound Error happened: ${e.message}", ip, AppLogger.autoTag(e))
        val resourcePath = e.resourcePath

        //logError(e, ip)
        return ResponseEntity
            .status(404)
            .body("Resource not found: $resourcePath")
    }


    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(e: HttpRequestMethodNotSupportedException, request: HttpServletRequest): ResponseEntity<String> {
        val ip = clientIpResolver.resolve(request)
        logRequestError("Wrong request method: ${e.method} not supported for endpoint ${request.requestURI}", ip, AppLogger.autoTag(e))

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
        val message = "Unreadable request body on ${request.method} ${request.requestURI} (ip=$ip): $cause"
        markRequestFailure(AppLogger.autoTag(e), message)
        AppLogger.warn(message, tag = AppLogger.autoTag(e))

        return ResponseEntity
            .badRequest()
            .body(mapOf("error" to "Invalid request body: $cause"))
    }

    /**
     * Missing or malformed query parameter or multipart part (no ?extensionid=, ?page=abc). A client
     * bug, not a server error: 400 with one warn line instead of the catch-all's 500 + stack trace.
     */
    @ExceptionHandler(
        ServletRequestBindingException::class,
        MissingServletRequestPartException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun handleBadRequestParameter(e: Exception, request: HttpServletRequest): ResponseEntity<String> {
        val ip = clientIpResolver.resolve(request)
        val error = when (e) {
            is MissingServletRequestParameterException -> "Missing request parameter '${e.parameterName}'"
            is MissingServletRequestPartException -> "Missing request part '${e.requestPartName}'"
            is MethodArgumentTypeMismatchException -> "Invalid value for parameter '${e.name}'"
            else -> e.message ?: "Invalid request parameters"
        }
        logRequestError("Bad request parameter on ${request.method} ${request.requestURI}: $error", ip, AppLogger.autoTag(e))

        return ResponseEntity
            .badRequest()
            .body(error)
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
        markRequestFailure("login_failed", "Invalid credentials")
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body("Invalid credentials")
    }

    //withOptimisticRetry gave up: the document kept changing under concurrent writes, the client can retry
    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLockingFailure(e: OptimisticLockingFailureException, request: HttpServletRequest): ResponseEntity<String> {
        val ip = clientIpResolver.resolve(request)
        logRequestError("Optimistic locking retries exhausted: ${e.message}", ip, AppLogger.autoTag(e))
        return ResponseEntity
            .status(409)
            .body("This entry was changed at the same time. Please try again.")
    }

    // Catch-all handler for any unhandled exceptions
    @ExceptionHandler(Exception::class)
    fun handleGeneralException(e: Exception, request: HttpServletRequest): ResponseEntity<String> {
        val ip = clientIpResolver.resolve(request)

        //No stack trace printing for badcredentials (Someone used a wrong username)
        if (e !is BadCredentialsException){
            logRequestError(
                "Unhandled server error: ${e.javaClass.simpleName} - ${e.message}", ip,
                AppLogger.autoTag(e), AppLogLevel.ERROR, error = e,
            )

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