package com.taskforge.engine

import com.taskforge.model.WorkflowTaskDefinition
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

data class TaskExecutionInput(
    val executionId: String,
    val attempt: Int,
    val task: WorkflowTaskDefinition,
    val resolvedConfig: JsonObject,
    val state: JsonObject,
)

enum class ApprovalTimeoutAction {
    APPROVE,
    REJECT,
    FAIL,
}

sealed interface TaskExecutionOutcome {
    data class Success(
        val output: JsonObject = JsonObject(emptyMap()),
        val statePatch: Map<String, JsonElement> = emptyMap(),
    ) : TaskExecutionOutcome

    data class Failure(
        val message: String,
        val retryable: Boolean,
        val output: JsonObject = JsonObject(emptyMap()),
    ) : TaskExecutionOutcome

    data class AwaitApproval(
        val timeoutMs: Long,
        val onTimeout: ApprovalTimeoutAction,
        val requiredIdentity: String? = null,
        val prompt: String? = null,
    ) : TaskExecutionOutcome
}

interface TaskHandler {
    val type: String
    suspend fun execute(input: TaskExecutionInput): TaskExecutionOutcome
}

class TaskHandlerRegistry(handlers: List<TaskHandler> = emptyList()) {
    private val byType = ConcurrentHashMap<String, TaskHandler>()

    init {
        handlers.forEach(::register)
    }

    fun register(handler: TaskHandler) {
        require(handler.type.isNotBlank()) { "Task handler type cannot be blank" }
        val existing = byType.putIfAbsent(handler.type, handler)
        if (existing != null) {
            throw IllegalArgumentException(
                "Duplicate task handler type '${handler.type}' registered by " +
                    "${existing::class.qualifiedName} and ${handler::class.qualifiedName}",
            )
        }
    }

    fun get(type: String): TaskHandler? = byType[type]

    fun knownTypes(): Set<String> = byType.keys.toSortedSet()
}
