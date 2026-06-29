package com.taskforge.engine

import com.taskforge.model.CreateWorkflowRequest
import com.taskforge.model.ExecutionStatus
import com.taskforge.model.StartExecutionRequest
import com.taskforge.model.TaskStatus
import com.taskforge.model.WorkflowTaskDefinition
import com.taskforge.tasks.ApprovalTaskHandler
import com.taskforge.tasks.AssertStateTaskHandler
import com.taskforge.tasks.SetStateTaskHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

class ExecutionServiceTest {
    @Test
    fun `rejects duplicate workflow id with conflict`() {
        val service = ExecutionService(
            registry = TaskHandlerRegistry(listOf(SetStateTaskHandler())),
        )
        val request = CreateWorkflowRequest(
            id = "wf-dup",
            name = "dup",
            tasks = listOf(
                WorkflowTaskDefinition(
                    id = "t1",
                    type = "set_state",
                    config = buildJsonObject {
                        put("values", JsonObject(mapOf("a" to JsonPrimitive(1))))
                    },
                ),
            ),
        )

        service.createWorkflow(request)
        val ex = assertThrows<ConflictException> {
            service.createWorkflow(request)
        }
        assertTrue(ex.message?.contains("already exists") == true)
    }

    @Test
    fun `rejects unknown task type with actionable validation`() {
        val service = ExecutionService(
            registry = TaskHandlerRegistry(listOf(SetStateTaskHandler())),
        )
        val ex = assertThrows<ValidationException> {
            service.createWorkflow(
                CreateWorkflowRequest(
                    name = "bad-workflow",
                    tasks = listOf(
                        WorkflowTaskDefinition(
                            id = "t1",
                            type = "missing_handler",
                            config = JsonObject(emptyMap()),
                        ),
                    ),
                ),
            )
        }
        assertTrue(ex.errors.any { it.field == "tasks.type" && it.taskId == "t1" })
    }

    @Test
    fun `rejects cyclic workflow`() {
        val service = ExecutionService(
            registry = TaskHandlerRegistry(listOf(SetStateTaskHandler())),
        )
        val ex = assertThrows<ValidationException> {
            service.createWorkflow(
                CreateWorkflowRequest(
                    name = "cycle-workflow",
                    tasks = listOf(
                        WorkflowTaskDefinition(
                            id = "a",
                            type = "set_state",
                            dependencies = listOf("b"),
                            config = buildJsonObject { put("values", JsonObject(mapOf("x" to JsonPrimitive(1)))) },
                        ),
                        WorkflowTaskDefinition(
                            id = "b",
                            type = "set_state",
                            dependencies = listOf("a"),
                            config = buildJsonObject { put("values", JsonObject(mapOf("y" to JsonPrimitive(2)))) },
                        ),
                    ),
                ),
            )
        }
        assertTrue(ex.errors.any { it.message.contains("Cycle detected") })
    }

    @Test
    fun `rejects invalid workflow and retry configuration`() {
        val service = ExecutionService(
            registry = TaskHandlerRegistry(listOf(SetStateTaskHandler())),
        )
        val ex = assertThrows<ValidationException> {
            service.createWorkflow(
                CreateWorkflowRequest(
                    name = " ",
                    tasks = listOf(
                        WorkflowTaskDefinition(
                            id = "bad",
                            type = "set_state",
                            timeoutMs = 0,
                            retryPolicy = com.taskforge.model.RetryPolicy(maxRetries = -1, backoffMs = -1),
                            config = buildJsonObject {
                                put("values", JsonObject(mapOf("x" to JsonPrimitive(1))))
                            },
                        ),
                    ),
                ),
            )
        }
        assertTrue(ex.errors.any { it.field == "name" })
        assertTrue(ex.errors.any { it.field == "tasks.timeoutMs" })
        assertTrue(ex.errors.any { it.field == "tasks.retryPolicy.maxRetries" })
        assertTrue(ex.errors.any { it.field == "tasks.retryPolicy.backoffMs" })
    }

    @Test
    fun `executes dependency chain and propagates state`() = runBlocking {
        val service = ExecutionService(
            registry = TaskHandlerRegistry(
                listOf(
                    SetStateTaskHandler(),
                    AssertStateTaskHandler(),
                ),
            ),
        )

        val workflow = service.createWorkflow(
            CreateWorkflowRequest(
                id = "wf-basic",
                name = "basic",
                tasks = listOf(
                    WorkflowTaskDefinition(
                        id = "seed",
                        type = "set_state",
                        config = buildJsonObject {
                            put("values", JsonObject(mapOf("build.image" to JsonPrimitive("taskforge:v1"))))
                        },
                    ),
                    WorkflowTaskDefinition(
                        id = "check",
                        type = "assert_state",
                        dependencies = listOf("seed"),
                        config = buildJsonObject {
                            put("path", JsonPrimitive("build.image"))
                            put("equals", JsonPrimitive("taskforge:v1"))
                        },
                    ),
                    WorkflowTaskDefinition(
                        id = "publish",
                        type = "set_state",
                        dependencies = listOf("check"),
                        config = buildJsonObject {
                            put("values", JsonObject(mapOf("deploy.image" to JsonPrimitive("\${tasks.check.actual}"))))
                        },
                    ),
                ),
            ),
        )

        val started = service.startExecution(StartExecutionRequest(workflowId = workflow.id))
        val completed = awaitExecution(service, started.id) { it.status == ExecutionStatus.SUCCEEDED }

        assertEquals(ExecutionStatus.SUCCEEDED, completed.status)
        val deployImage = resolvePath(completed.state, "deploy.image")
        assertEquals(JsonPrimitive("taskforge:v1"), deployImage)
        assertEquals(
            TaskStatus.SUCCEEDED,
            completed.tasks.single { it.taskId == "publish" }.status,
        )
    }

    @Test
    fun `supports approval-gated execution`() = runBlocking {
        val service = ExecutionService(
            registry = TaskHandlerRegistry(
                listOf(
                    ApprovalTaskHandler(),
                    SetStateTaskHandler(),
                ),
            ),
        )

        val workflow = service.createWorkflow(
            CreateWorkflowRequest(
                id = "wf-approval",
                name = "approval",
                tasks = listOf(
                    WorkflowTaskDefinition(
                        id = "gate",
                        type = "approval",
                        config = buildJsonObject {
                            put("requiredIdentity", JsonPrimitive("ops-lead"))
                            put("timeoutMs", JsonPrimitive(20_000))
                            put("onTimeout", JsonPrimitive("FAIL"))
                        },
                    ),
                    WorkflowTaskDefinition(
                        id = "after",
                        type = "set_state",
                        dependencies = listOf("gate"),
                        config = buildJsonObject {
                            put("values", JsonObject(mapOf("release.approved" to JsonPrimitive(true))))
                        },
                    ),
                ),
            ),
        )

        val started = service.startExecution(StartExecutionRequest(workflow.id))
        val waiting = awaitExecution(service, started.id) { it.status == ExecutionStatus.WAITING_APPROVAL }
        assertEquals(TaskStatus.WAITING_APPROVAL, waiting.tasks.single { it.taskId == "gate" }.status)

        val resolved = service.resolveApproval(
            executionId = started.id,
            taskId = "gate",
            request = com.taskforge.model.ResolveApprovalRequest(
                approved = true,
                actor = "ops-lead",
            ),
        )
        assertTrue(resolved.status == ExecutionStatus.RUNNING || resolved.status == ExecutionStatus.SUCCEEDED)
        assertEquals(TaskStatus.SUCCEEDED, resolved.tasks.single { it.taskId == "gate" }.status)

        val completed = awaitExecution(service, started.id) { it.status == ExecutionStatus.SUCCEEDED }
        assertEquals(JsonPrimitive(true), resolvePath(completed.state, "release.approved"))
    }

    @Test
    fun `retries transient task failures`() = runBlocking {
        val attempts = AtomicInteger(0)
        val flaky = object : TaskHandler {
            override val type: String = "flaky"

            override suspend fun execute(input: TaskExecutionInput): TaskExecutionOutcome {
                val n = attempts.incrementAndGet()
                return if (n < 3) {
                    TaskExecutionOutcome.Failure("transient", retryable = true)
                } else {
                    TaskExecutionOutcome.Success(
                        output = buildJsonObject { put("attempt", JsonPrimitive(n)) },
                    )
                }
            }
        }

        val service = ExecutionService(TaskHandlerRegistry(listOf(flaky)))
        val workflow = service.createWorkflow(
            CreateWorkflowRequest(
                name = "retry",
                tasks = listOf(
                    WorkflowTaskDefinition(
                        id = "flaky-step",
                        type = "flaky",
                        retryPolicy = com.taskforge.model.RetryPolicy(maxRetries = 2, backoffMs = 5),
                    ),
                ),
            ),
        )

        val started = service.startExecution(StartExecutionRequest(workflow.id))
        val completed = awaitExecution(service, started.id) { it.status == ExecutionStatus.SUCCEEDED }
        val task = completed.tasks.single()
        assertEquals(3, task.attempts)
        assertEquals(TaskStatus.SUCCEEDED, task.status)
    }

    @Test
    fun `treats unhandled task exceptions as permanent failures`() = runBlocking {
        val attempts = AtomicInteger(0)
        val crashing = object : TaskHandler {
            override val type: String = "crashing"

            override suspend fun execute(input: TaskExecutionInput): TaskExecutionOutcome {
                attempts.incrementAndGet()
                throw IllegalStateException("boom")
            }
        }

        val service = ExecutionService(TaskHandlerRegistry(listOf(crashing)))
        val workflow = service.createWorkflow(
            CreateWorkflowRequest(
                name = "permanent-exception",
                tasks = listOf(
                    WorkflowTaskDefinition(
                        id = "crash-step",
                        type = "crashing",
                        retryPolicy = com.taskforge.model.RetryPolicy(maxRetries = 5, backoffMs = 5),
                    ),
                ),
            ),
        )

        val started = service.startExecution(StartExecutionRequest(workflow.id))
        val completed = awaitExecution(service, started.id) { it.status == ExecutionStatus.FAILED }
        val task = completed.tasks.single()
        assertEquals(1, attempts.get())
        assertEquals(1, task.attempts)
        assertEquals(TaskStatus.FAILED, task.status)
    }

    @Test
    fun `provides consistent snapshots during concurrent reads`() = runBlocking {
        val slow = object : TaskHandler {
            override val type: String = "slow"

            override suspend fun execute(input: TaskExecutionInput): TaskExecutionOutcome {
                delay(10)
                return TaskExecutionOutcome.Success(output = buildJsonObject { put("task", JsonPrimitive(input.task.id)) })
            }
        }
        val service = ExecutionService(TaskHandlerRegistry(listOf(slow)))
        val workflow = service.createWorkflow(
            CreateWorkflowRequest(
                name = "snapshot-consistency",
                tasks = (1..20).map { i -> WorkflowTaskDefinition(id = "t$i", type = "slow") },
            ),
        )

        val started = service.startExecution(StartExecutionRequest(workflow.id))
        val reader = launch {
            repeat(200) {
                val snapshot = service.getExecution(started.id)
                assertEquals(20, snapshot.tasks.size)
                delay(1)
            }
        }

        val completed = awaitExecution(service, started.id) { it.status == ExecutionStatus.SUCCEEDED }
        reader.join()
        assertEquals(ExecutionStatus.SUCCEEDED, completed.status)
    }

    @Test
    fun `allows registering a new task type without engine changes`() = runBlocking {
        val custom = object : TaskHandler {
            override val type: String = "custom_echo"
            override suspend fun execute(input: TaskExecutionInput): TaskExecutionOutcome {
                val value = resolvePath(input.state, "input.value") ?: JsonPrimitive("missing")
                return TaskExecutionOutcome.Success(
                    output = buildJsonObject { put("echo", value) },
                    statePatch = mapOf("result.echo" to value),
                )
            }
        }
        val service = ExecutionService(TaskHandlerRegistry(listOf(custom)))
        val workflow = service.createWorkflow(
            CreateWorkflowRequest(
                name = "extensibility",
                tasks = listOf(WorkflowTaskDefinition(id = "x", type = "custom_echo")),
            ),
        )

        val started = service.startExecution(
            StartExecutionRequest(
                workflowId = workflow.id,
                initialState = buildJsonObject {
                    put("input", JsonObject(mapOf("value" to JsonPrimitive("hello"))))
                },
            ),
        )
        val completed = awaitExecution(service, started.id) { it.status == ExecutionStatus.SUCCEEDED }
        assertEquals(JsonPrimitive("hello"), resolvePath(completed.state, "result.echo"))
    }

    private suspend fun awaitExecution(
        service: ExecutionService,
        executionId: String,
        predicate: (com.taskforge.model.ExecutionView) -> Boolean,
    ): com.taskforge.model.ExecutionView {
        repeat(100) {
            val snapshot = service.getExecution(executionId)
            if (predicate(snapshot)) return snapshot
            delay(20)
        }
        throw AssertionError("Execution did not reach expected state in time")
    }
}
