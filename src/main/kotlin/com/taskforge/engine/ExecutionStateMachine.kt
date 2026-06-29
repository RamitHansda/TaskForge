package com.taskforge.engine

import com.taskforge.model.ApprovalView
import com.taskforge.model.ExecutionStatus
import com.taskforge.model.ExecutionView
import com.taskforge.model.ResolveApprovalRequest
import com.taskforge.model.TaskExecutionView
import com.taskforge.model.TaskStatus
import com.taskforge.model.WorkflowDefinition
import com.taskforge.model.WorkflowTaskDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.util.UUID

internal class ExecutionStateMachine {
    fun createExecutionRuntime(
        workflow: WorkflowDefinition,
        initialState: JsonObject,
    ): ExecutionRuntime {
        val executionId = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val taskStates = workflow.tasks.associate { it.id to MutableTaskState() }.toMutableMap()
        return ExecutionRuntime(
            id = executionId,
            workflow = workflow,
            startedAt = now,
            status = ExecutionStatus.RUNNING,
            state = initialState,
            taskStates = taskStates,
        )
    }

    suspend fun currentState(runtime: ExecutionRuntime): JsonObject =
        runtime.mutex.withLock { runtime.state }

    suspend fun cancelExecution(runtime: ExecutionRuntime): List<Job> {
        var jobsToJoin: List<Job> = emptyList()
        runtime.mutex.withLock {
            if (runtime.status in terminalExecutionStates) return@withLock
            runtime.cancelRequested = true
            runtime.status = ExecutionStatus.CANCELLING
            val now = Instant.now().toString()

            runtime.pendingApprovals.values.forEach { approval ->
                approval.timeoutJob.cancel()
                runtime.taskStates.getValue(approval.taskId).apply {
                    status = TaskStatus.CANCELLED
                    endedAt = now
                    error = "Execution cancelled while waiting for approval"
                }
            }
            runtime.pendingApprovals.clear()

            runtime.taskStates.values
                .filter { it.status == TaskStatus.PENDING }
                .forEach {
                    it.status = TaskStatus.CANCELLED
                    it.endedAt = now
                    it.error = "Execution cancelled before start"
                }

            jobsToJoin = runtime.runningJobs.values.toList()
            jobsToJoin.forEach { it.cancel(CancellationException("Execution cancelled")) }
        }
        return jobsToJoin
    }

    suspend fun resolveApproval(
        runtime: ExecutionRuntime,
        taskId: String,
        request: ResolveApprovalRequest,
    ) {
        runtime.mutex.withLock {
            val pending = runtime.pendingApprovals[taskId]
                ?: throw ConflictException("Task '$taskId' is not waiting for approval")

            if (pending.requiredIdentity != null && pending.requiredIdentity != request.actor) {
                throw ConflictException(
                    "Approval for task '$taskId' requires identity '${pending.requiredIdentity}'",
                )
            }

            pending.timeoutJob.cancel()
            runtime.pendingApprovals.remove(taskId)

            val taskState = runtime.taskStates.getValue(taskId)
            val now = Instant.now().toString()
            val output = buildJsonObject {
                put("approved", JsonPrimitive(request.approved))
                put("actor", JsonPrimitive(request.actor))
                request.comment?.let { put("comment", JsonPrimitive(it)) }
                put("resolvedAt", JsonPrimitive(now))
            }
            taskState.output = output
            taskState.endedAt = now

            if (request.approved) {
                taskState.status = TaskStatus.SUCCEEDED
                runtime.state = runtime.state.withPath("tasks.$taskId", output)
            } else {
                taskState.status = TaskStatus.FAILED
                taskState.error = "Approval rejected by '${request.actor}'"
                runtime.state = runtime.state.withPath("tasks.$taskId", output)
            }
            updateExecutionStatus(runtime)
        }
    }

    suspend fun scheduleReadyTasks(
        runtime: ExecutionRuntime,
        launchTask: (WorkflowTaskDefinition, Int) -> Job,
    ) {
        runtime.mutex.withLock {
            if (runtime.status in terminalExecutionStates) return
            val toLaunch = mutableListOf<WorkflowTaskDefinition>()
            val now = Instant.now().toString()
            runtime.workflow.tasks.forEach { task ->
                val taskState = runtime.taskStates.getValue(task.id)
                if (taskState.status != TaskStatus.PENDING) {
                    return@forEach
                }

                val dependencyStatuses = task.dependencies.map { runtime.taskStates.getValue(it).status }
                if (dependencyStatuses.any { !it.isTerminal() }) {
                    return@forEach
                }

                if (runtime.cancelRequested) {
                    taskState.status = TaskStatus.CANCELLED
                    taskState.endedAt = now
                    taskState.error = "Execution cancelled before start"
                    return@forEach
                }

                if (dependencyStatuses.any { it.blocksDownstream() }) {
                    taskState.status = TaskStatus.SKIPPED_UPSTREAM
                    taskState.endedAt = now
                    taskState.error = "Skipped due to upstream failure"
                    return@forEach
                }

                val conditionSatisfied = try {
                    evaluateCondition(task.condition, runtime.state)
                } catch (ex: Exception) {
                    taskState.status = TaskStatus.FAILED
                    taskState.endedAt = now
                    taskState.error = "Condition evaluation failed: ${ex.message}"
                    false
                }

                if (taskState.status == TaskStatus.FAILED) {
                    return@forEach
                }
                if (!conditionSatisfied) {
                    taskState.status = TaskStatus.SKIPPED_CONDITION
                    taskState.endedAt = now
                    return@forEach
                }
                toLaunch += task
            }

            toLaunch.forEach { task ->
                val state = runtime.taskStates.getValue(task.id)
                state.status = TaskStatus.RUNNING
                state.attempts += 1
                state.startedAt = state.startedAt ?: Instant.now().toString()
                state.error = null
                val job = launchTask(task, state.attempts)
                runtime.runningJobs[task.id] = job
            }

            updateExecutionStatus(runtime)
        }
    }

    suspend fun markTaskCancelled(runtime: ExecutionRuntime, taskId: String) {
        runtime.mutex.withLock {
            runtime.runningJobs.remove(taskId)
            val state = runtime.taskStates.getValue(taskId)
            state.status = TaskStatus.CANCELLED
            state.endedAt = Instant.now().toString()
            state.error = "Task cancelled"
            updateExecutionStatus(runtime)
        }
    }

    suspend fun markTaskSuccess(
        runtime: ExecutionRuntime,
        task: WorkflowTaskDefinition,
        output: JsonObject,
        statePatch: Map<String, JsonElement>,
    ) {
        runtime.mutex.withLock {
            runtime.runningJobs.remove(task.id)
            val now = Instant.now().toString()
            runtime.taskStates.getValue(task.id).apply {
                status = TaskStatus.SUCCEEDED
                endedAt = now
                this.output = output
            }

            var newState = runtime.state.withPath("tasks.${task.id}", output)
            statePatch.forEach { (path, value) ->
                newState = newState.withPath(path, value)
            }
            runtime.state = newState
            updateExecutionStatus(runtime)
        }
    }

    suspend fun markTaskFailure(
        runtime: ExecutionRuntime,
        task: WorkflowTaskDefinition,
        attempt: Int,
        message: String,
        retryable: Boolean,
        timedOut: Boolean,
        output: JsonObject,
    ): Long? {
        var retryDelayMs: Long? = null
        runtime.mutex.withLock {
            runtime.runningJobs.remove(task.id)
            val state = runtime.taskStates.getValue(task.id)
            val now = Instant.now().toString()
            val canRetry = retryable && attempt <= task.retryPolicy.maxRetries
            if (canRetry) {
                state.status = TaskStatus.PENDING
                state.error = "Attempt $attempt failed: $message"
                retryDelayMs = task.retryPolicy.backoffMs * attempt
            } else {
                state.status = if (timedOut) TaskStatus.TIMED_OUT else TaskStatus.FAILED
                state.endedAt = now
                state.error = message
                state.output = output
                runtime.state = runtime.state.withPath("tasks.${task.id}", output)
            }
            updateExecutionStatus(runtime)
        }
        return retryDelayMs
    }

    suspend fun markTaskAwaitApproval(
        runtime: ExecutionRuntime,
        task: WorkflowTaskDefinition,
        outcome: TaskExecutionOutcome.AwaitApproval,
        timeoutJob: Job,
    ) {
        runtime.mutex.withLock {
            runtime.runningJobs.remove(task.id)
            val now = Instant.now()
            val deadline = now.plusMillis(outcome.timeoutMs)
            runtime.pendingApprovals[task.id] = PendingApprovalState(
                taskId = task.id,
                requiredIdentity = outcome.requiredIdentity,
                prompt = outcome.prompt,
                requestedAt = now.toString(),
                deadlineAt = deadline.toString(),
                onTimeout = outcome.onTimeout,
                timeoutJob = timeoutJob,
            )
            runtime.taskStates.getValue(task.id).apply {
                status = TaskStatus.WAITING_APPROVAL
                endedAt = null
            }
            updateExecutionStatus(runtime)
        }
    }

    suspend fun handleApprovalTimeout(runtime: ExecutionRuntime, taskId: String): Boolean {
        var changed = false
        runtime.mutex.withLock {
            val pending = runtime.pendingApprovals.remove(taskId) ?: return@withLock
            changed = true
            val taskState = runtime.taskStates.getValue(taskId)
            val now = Instant.now().toString()
            when (pending.onTimeout) {
                ApprovalTimeoutAction.APPROVE -> {
                    val output = buildJsonObject {
                        put("approved", JsonPrimitive(true))
                        put("actor", JsonPrimitive("system-timeout"))
                        put("reason", JsonPrimitive("approval timeout auto-approved"))
                    }
                    taskState.status = TaskStatus.SUCCEEDED
                    taskState.output = output
                    taskState.endedAt = now
                    runtime.state = runtime.state.withPath("tasks.$taskId", output)
                }

                ApprovalTimeoutAction.REJECT -> {
                    val output = buildJsonObject {
                        put("approved", JsonPrimitive(false))
                        put("actor", JsonPrimitive("system-timeout"))
                        put("reason", JsonPrimitive("approval timeout auto-rejected"))
                    }
                    taskState.status = TaskStatus.FAILED
                    taskState.output = output
                    taskState.endedAt = now
                    taskState.error = "Approval timed out and was auto-rejected"
                    runtime.state = runtime.state.withPath("tasks.$taskId", output)
                }

                ApprovalTimeoutAction.FAIL -> {
                    taskState.status = TaskStatus.TIMED_OUT
                    taskState.endedAt = now
                    taskState.error = "Approval timed out"
                    runtime.state = runtime.state.withPath(
                        "tasks.$taskId",
                        JsonObject(
                            mapOf(
                                "approved" to JsonPrimitive(false),
                                "reason" to JsonPrimitive("approval timed out"),
                            ),
                        ),
                    )
                }
            }
            updateExecutionStatus(runtime)
        }
        return changed
    }

    suspend fun snapshot(runtime: ExecutionRuntime): ExecutionView =
        runtime.mutex.withLock {
            val taskViews = runtime.workflow.tasks.map { task ->
                val state = runtime.taskStates.getValue(task.id)
                TaskExecutionView(
                    taskId = task.id,
                    type = task.type,
                    status = state.status,
                    attempts = state.attempts,
                    startedAt = state.startedAt,
                    endedAt = state.endedAt,
                    error = state.error,
                    output = state.output,
                )
            }
            val approvals = runtime.pendingApprovals.values.map {
                ApprovalView(
                    taskId = it.taskId,
                    requiredIdentity = it.requiredIdentity,
                    prompt = it.prompt,
                    requestedAt = it.requestedAt,
                    deadlineAt = it.deadlineAt,
                )
            }
            ExecutionView(
                id = runtime.id,
                workflowId = runtime.workflow.id,
                status = runtime.status,
                startedAt = runtime.startedAt,
                endedAt = runtime.endedAt,
                state = runtime.state,
                tasks = taskViews,
                approvals = approvals,
            )
        }

    private fun updateExecutionStatus(runtime: ExecutionRuntime) {
        if (runtime.status in terminalExecutionStates) return
        if (runtime.cancelRequested) {
            runtime.status = if (runtime.runningJobs.isEmpty()) ExecutionStatus.CANCELLED else ExecutionStatus.CANCELLING
            if (runtime.status == ExecutionStatus.CANCELLED) {
                runtime.endedAt = runtime.endedAt ?: Instant.now().toString()
            }
            return
        }

        val taskStatuses = runtime.taskStates.values.map { it.status }
        val allTerminal = taskStatuses.all { it.isTerminal() }
        when {
            allTerminal && taskStatuses.any { it == TaskStatus.FAILED || it == TaskStatus.TIMED_OUT } -> {
                runtime.status = ExecutionStatus.FAILED
                runtime.endedAt = runtime.endedAt ?: Instant.now().toString()
            }

            allTerminal -> {
                runtime.status = ExecutionStatus.SUCCEEDED
                runtime.endedAt = runtime.endedAt ?: Instant.now().toString()
            }

            runtime.pendingApprovals.isNotEmpty() && runtime.runningJobs.isEmpty() -> {
                runtime.status = ExecutionStatus.WAITING_APPROVAL
            }

            else -> {
                runtime.status = ExecutionStatus.RUNNING
            }
        }
    }
}
