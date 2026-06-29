package com.taskforge

import com.taskforge.engine.ConflictException
import com.taskforge.engine.NotFoundException
import com.taskforge.engine.ValidationException
import com.taskforge.model.ErrorResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler(private val json: Json) {
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(SerializationException::class)
    fun handleSerialization(ex: SerializationException): ResponseEntity<String> {
        logger.warn("request_deserialization_failed message={}", ex.message)
        return build(HttpStatus.BAD_REQUEST, ErrorResponse("Invalid JSON payload: ${ex.message}"))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<String> {
        logger.warn("request_validation_failed message={}", ex.message)
        return build(HttpStatus.BAD_REQUEST, ErrorResponse(ex.message ?: "Invalid request"))
    }

    @ExceptionHandler(ValidationException::class)
    fun handleValidation(ex: ValidationException): ResponseEntity<String> {
        logger.warn("workflow_validation_failed error_count={}", ex.errors.size)
        return build(
            HttpStatus.BAD_REQUEST,
            ErrorResponse(ex.message ?: "Validation failed", ex.errors),
        )
    }

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(ex: NotFoundException): ResponseEntity<String> {
        logger.info("resource_not_found message={}", ex.message)
        return build(HttpStatus.NOT_FOUND, ErrorResponse(ex.message ?: "Not found"))
    }

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(ex: ConflictException): ResponseEntity<String> {
        logger.warn("request_conflict message={}", ex.message)
        return build(HttpStatus.CONFLICT, ErrorResponse(ex.message ?: "Conflict"))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnhandled(ex: Exception): ResponseEntity<String> {
        logger.error("unhandled_exception", ex)
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorResponse(ex.message ?: "Internal error"))
    }

    private fun build(status: HttpStatus, body: ErrorResponse): ResponseEntity<String> =
        ResponseEntity.status(status).body(json.encodeToString(body))
}
