package com.taskforge.tasks

import com.taskforge.engine.TaskExecutionInput
import com.taskforge.engine.TaskExecutionOutcome
import com.taskforge.engine.TaskHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class HttpTaskHandler(
    private val httpClient: HttpClient = HttpClient.newBuilder().build(),
    private val json: Json = Json,
) : TaskHandler {
    override val type: String = "http"

    override suspend fun execute(input: TaskExecutionInput): TaskExecutionOutcome = withContext(Dispatchers.IO) {
        val config = input.resolvedConfig
        val url = config["url"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext TaskExecutionOutcome.Failure(
                message = "http task requires config.url",
                retryable = false,
            )
        val method = config["method"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "GET"
        val requestTimeoutMs = config["requestTimeoutMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 30_000L
        val bodyElement = config["body"]
        val bodyString = bodyElement?.let {
            if (it is JsonPrimitive && it.isString) it.content else json.encodeToString(JsonElement.serializer(), it)
        }
        val headers = (config["headers"] as? JsonObject).orEmpty()
        val allowedStatuses = parseStatusSet(config["allowedStatuses"])

        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(requestTimeoutMs))
        headers.forEach { (name, value) ->
            builder.header(name, value.jsonPrimitive.contentOrNull ?: value.toString())
        }

        when {
            method == "GET" || method == "DELETE" -> builder.method(
                method,
                HttpRequest.BodyPublishers.ofString(bodyString ?: ""),
            )

            else -> builder.method(
                method,
                HttpRequest.BodyPublishers.ofString(bodyString ?: ""),
            )
        }

        try {
            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            val statusCode = response.statusCode()
            val output = JsonObject(
                mapOf(
                    "status" to JsonPrimitive(statusCode),
                    "body" to JsonPrimitive(response.body()),
                    "headers" to JsonObject(
                        response.headers().map().mapValues { (_, values) ->
                            JsonPrimitive(values.joinToString(","))
                        },
                    ),
                ),
            )
            val successful = when {
                allowedStatuses.isNotEmpty() -> statusCode in allowedStatuses
                else -> statusCode in 200..299
            }

            if (successful) {
                TaskExecutionOutcome.Success(output = output)
            } else {
                TaskExecutionOutcome.Failure(
                    message = "HTTP request failed with status $statusCode",
                    retryable = statusCode >= 500 || statusCode == 429,
                    output = output,
                )
            }
        } catch (ex: Exception) {
            TaskExecutionOutcome.Failure(
                message = "HTTP request failed: ${ex.message}",
                retryable = true,
            )
        }
    }

    private fun parseStatusSet(element: JsonElement?): Set<Int> {
        val array = element as? JsonArray ?: return emptySet()
        return array.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }.toSet()
    }
}
