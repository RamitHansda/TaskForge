package com.taskforge.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class RetryPolicy(
    val maxRetries: Int = 0,
    val backoffMs: Long = 0,
)

@Serializable
data class WorkflowTaskDefinition(
    val id: String,
    val type: String,
    val dependencies: List<String> = emptyList(),
    val condition: JsonElement? = null,
    val timeoutMs: Long? = null,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val config: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class WorkflowDefinition(
    val id: String,
    val name: String,
    val tasks: List<WorkflowTaskDefinition>,
    val createdAt: String,
)

@Serializable
data class CreateWorkflowRequest(
    val id: String? = null,
    val name: String,
    val tasks: List<WorkflowTaskDefinition>,
)

@Serializable
data class ValidationError(
    val taskId: String? = null,
    val field: String,
    val message: String,
)

@Serializable
data class ErrorResponse(
    val message: String,
    val validationErrors: List<ValidationError> = emptyList(),
)

@Serializable
data class StartExecutionRequest(
    val workflowId: String,
    val initialState: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ResolveApprovalRequest(
    val approved: Boolean,
    val actor: String,
    val comment: String? = null,
)

@Serializable
enum class ExecutionStatus {
    RUNNING,
    WAITING_APPROVAL,
    CANCELLING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

@Serializable
enum class TaskStatus {
    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    SKIPPED_CONDITION,
    SKIPPED_UPSTREAM,
    CANCELLED,
}

@Serializable
data class ApprovalView(
    val taskId: String,
    val requiredIdentity: String? = null,
    val prompt: String? = null,
    val requestedAt: String,
    val deadlineAt: String,
)

@Serializable
data class TaskExecutionView(
    val taskId: String,
    val type: String,
    val status: TaskStatus,
    val attempts: Int,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val error: String? = null,
    val output: JsonObject? = null,
)

@Serializable
data class ExecutionView(
    val id: String,
    val workflowId: String,
    val status: ExecutionStatus,
    val startedAt: String,
    val endedAt: String? = null,
    val state: JsonObject,
    val tasks: List<TaskExecutionView>,
    val approvals: List<ApprovalView> = emptyList(),
)
