package com.taskforge.engine

import com.taskforge.model.CreateWorkflowRequest
import com.taskforge.model.ExecutionView
import com.taskforge.model.ResolveApprovalRequest
import com.taskforge.model.StartExecutionRequest
import com.taskforge.model.ValidationError
import com.taskforge.model.WorkflowDefinition
import com.taskforge.model.WorkflowTaskDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

class NotFoundException(message: String) : RuntimeException(message)
class ValidationException(message: String, val errors: List<ValidationError>) : RuntimeException(message)
class ConflictException(message: String) : RuntimeException(message)

class ExecutionService(
    private val registry: TaskHandlerRegistry,
    private val defaultTaskTimeoutMs: Long = 30_000L,
    private val cancelGraceMs: Long = 5_000L,
) {
    private val validator = WorkflowValidator(registry)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = ExecutionStore()
    private val stateMachine = ExecutionStateMachine()
    private val scheduler = ExecutionScheduler(scope)

    fun createWorkflow(request: CreateWorkflowRequest): WorkflowDefinition {
        val workflowId = request.id ?: UUID.randomUUID().toString()
        val workflow = WorkflowDefinition(
            id = workflowId,
            name = request.name,
            tasks = request.tasks,
            createdAt = Instant.now().toString(),
        )
        val errors = validator.validate(workflow)
        if (errors.isNotEmpty()) {
            throw ValidationException("Workflow validation failed", errors)
        }
        if (!store.saveWorkflow(workflow)) {
            throw ConflictException("Workflow '$workflowId' already exists")
        }
        return workflow
    }

    fun getWorkflow(workflowId: String): WorkflowDefinition =
        store.getWorkflow(workflowId) ?: throw NotFoundException("Workflow '$workflowId' was not found")

    fun startExecution(request: StartExecutionRequest): ExecutionView {
        val workflow = store.getWorkflow(request.workflowId)
            ?: throw NotFoundException("Workflow '${request.workflowId}' was not found")
        val runtime = stateMachine.createExecutionRuntime(workflow, request.initialState)
        store.saveExecution(runtime)
        requestSchedule(runtime)
        return runBlocking { stateMachine.snapshot(runtime) }
    }

    fun getExecution(executionId: String): ExecutionView {
        val runtime = store.getExecution(executionId)
            ?: throw NotFoundException("Execution '$executionId' was not found")
        return runBlocking { stateMachine.snapshot(runtime) }
    }

    suspend fun cancelExecution(executionId: String): ExecutionView {
        val runtime = store.getExecution(executionId)
            ?: throw NotFoundException("Execution '$executionId' was not found")
        val jobsToJoin = stateMachine.cancelExecution(runtime)
        withTimeoutOrNull(cancelGraceMs) {
            jobsToJoin.joinAll()
        } ?: jobsToJoin.forEach { it.cancel(CancellationException("Forced cancellation after grace window")) }
        requestSchedule(runtime)
        return stateMachine.snapshot(runtime)
    }

    suspend fun resolveApproval(
        executionId: String,
        taskId: String,
        request: ResolveApprovalRequest,
    ): ExecutionView {
        val runtime = store.getExecution(executionId)
            ?: throw NotFoundException("Execution '$executionId' was not found")
        stateMachine.resolveApproval(runtime, taskId, request)
        requestSchedule(runtime)
        return stateMachine.snapshot(runtime)
    }

    private fun requestSchedule(runtime: ExecutionRuntime) {
        scheduler.requestSchedule(runtime) { cycleRuntime ->
            stateMachine.scheduleReadyTasks(cycleRuntime) { task, attempt ->
                scope.launch {
                    executeTask(cycleRuntime, task, attempt)
                }
            }
        }
    }

    private suspend fun executeTask(
        runtime: ExecutionRuntime,
        task: WorkflowTaskDefinition,
        attempt: Int,
    ) {
        val handler = registry.get(task.type)
        if (handler == null) {
            val retryDelayMs = stateMachine.markTaskFailure(
                runtime = runtime,
                task = task,
                attempt = attempt,
                message = "No task handler registered for type '${task.type}'",
                retryable = false,
                timedOut = false,
                output = JsonObject(emptyMap()),
            )
            scheduleRetryIfNeeded(runtime, retryDelayMs)
            return
        }

        val snapshotState = stateMachine.currentState(runtime)
        val resolvedConfig = try {
            renderTemplate(task.config, snapshotState) as JsonObject
        } catch (ex: Exception) {
            val retryDelayMs = stateMachine.markTaskFailure(
                runtime = runtime,
                task = task,
                attempt = attempt,
                message = "Failed to resolve task config: ${ex.message}",
                retryable = false,
                timedOut = false,
                output = JsonObject(emptyMap()),
            )
            scheduleRetryIfNeeded(runtime, retryDelayMs)
            return
        }

        val timeoutMs = task.timeoutMs ?: defaultTaskTimeoutMs
        var timedOut = false
        val outcome = try {
            withTimeout(timeoutMs) {
                handler.execute(
                    TaskExecutionInput(
                        executionId = runtime.id,
                        attempt = attempt,
                        task = task,
                        resolvedConfig = resolvedConfig,
                        state = snapshotState,
                    ),
                )
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            timedOut = true
            TaskExecutionOutcome.Failure(
                message = "Task '${task.id}' timed out after ${timeoutMs}ms",
                retryable = true,
            )
        } catch (ex: CancellationException) {
            stateMachine.markTaskCancelled(runtime, task.id)
            requestSchedule(runtime)
            return
        } catch (ex: Exception) {
            TaskExecutionOutcome.Failure(
                message = "Task execution failed with exception: ${ex.message}",
                retryable = false,
            )
        }

        when (outcome) {
            is TaskExecutionOutcome.Success -> {
                stateMachine.markTaskSuccess(runtime, task, outcome.output, outcome.statePatch)
                requestSchedule(runtime)
            }

            is TaskExecutionOutcome.Failure -> {
                val retryDelayMs = stateMachine.markTaskFailure(
                    runtime = runtime,
                    task = task,
                    attempt = attempt,
                    message = outcome.message,
                    retryable = outcome.retryable,
                    timedOut = timedOut,
                    output = outcome.output,
                )
                scheduleRetryIfNeeded(runtime, retryDelayMs)
            }

            is TaskExecutionOutcome.AwaitApproval -> {
                val timeoutJob = scope.launch {
                    delay(outcome.timeoutMs)
                    if (stateMachine.handleApprovalTimeout(runtime, task.id)) {
                        requestSchedule(runtime)
                    }
                }
                stateMachine.markTaskAwaitApproval(runtime, task, outcome, timeoutJob)
                requestSchedule(runtime)
            }
        }
    }

    private fun scheduleRetryIfNeeded(runtime: ExecutionRuntime, retryDelayMs: Long?) {
        if (retryDelayMs != null) {
            scope.launch {
                delay(retryDelayMs)
                requestSchedule(runtime)
            }
        } else {
            requestSchedule(runtime)
        }
    }
}
