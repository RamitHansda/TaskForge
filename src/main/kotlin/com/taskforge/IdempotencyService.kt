package com.taskforge

import com.taskforge.engine.ConflictException
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class IdempotencyService(
    private val ttl: Duration = Duration.ofHours(24),
) {
    private data class IdempotencyEntry(
        val requestHash: String,
        val createdAtMillis: Long = System.currentTimeMillis(),
        var responseStatusCode: Int? = null,
        var responseBody: String? = null,
    )

    private val entries = ConcurrentHashMap<String, IdempotencyEntry>()

    fun execute(
        scope: String,
        key: String,
        requestFingerprint: String,
        operation: () -> ResponseEntity<String>,
    ): ResponseEntity<String> {
        val normalizedKey = key.trim()
        require(normalizedKey.isNotBlank()) { "Idempotency-Key header is required for this endpoint" }
        cleanupExpiredEntries()

        val storageKey = "$scope:$normalizedKey"
        val requestHash = sha256Hex(requestFingerprint)
        val newEntry = IdempotencyEntry(requestHash = requestHash)
        val existing = entries.putIfAbsent(storageKey, newEntry)
        if (existing == null) {
            return try {
                val response = operation()
                synchronized(newEntry) {
                    newEntry.responseStatusCode = response.statusCode.value()
                    newEntry.responseBody = response.body.orEmpty()
                }
                withReplayHeader(response, replayed = false)
            } catch (ex: Exception) {
                entries.remove(storageKey, newEntry)
                throw ex
            }
        }

        synchronized(existing) {
            if (existing.requestHash != requestHash) {
                throw ConflictException(
                    "Idempotency-Key '$normalizedKey' was already used for a different request",
                )
            }

            val statusCode = existing.responseStatusCode
            val body = existing.responseBody
            if (statusCode != null && body != null) {
                return withReplayHeader(
                    ResponseEntity.status(statusCode).body(body),
                    replayed = true,
                )
            }
        }

        throw ConflictException("A request with Idempotency-Key '$normalizedKey' is already in progress")
    }

    private fun cleanupExpiredEntries() {
        val nowMillis = System.currentTimeMillis()
        val ttlMillis = ttl.toMillis()
        entries.entries.removeIf { (_, entry) -> nowMillis - entry.createdAtMillis > ttlMillis }
    }

    private fun withReplayHeader(response: ResponseEntity<String>, replayed: Boolean): ResponseEntity<String> {
        val headers = HttpHeaders()
        headers.putAll(response.headers)
        headers.set(IDEMPOTENCY_REPLAYED_HEADER, replayed.toString())
        return ResponseEntity.status(response.statusCode)
            .headers(headers)
            .body(response.body)
    }

    private fun sha256Hex(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"
    }
}
