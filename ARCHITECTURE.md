# ARCHITECTURE

## 1) Overview

TaskForge is implemented as a Spring Boot HTTP service that stores workflow definitions and execution state in memory.  
The orchestration engine schedules DAG tasks concurrently when dependencies are satisfied.

Core layers:

1. **API Layer (`TaskForgeController.kt`, `GlobalExceptionHandler.kt`)**
   - HTTP/JSON routes
   - request decoding + centralized error mapping
2. **Engine Orchestration Layer (`ExecutionService.kt`)**
   - coordinates workflow APIs with engine components
   - delegates runtime transitions and scheduling concerns
3. **Engine State & Scheduling Components**
   - `ExecutionStore.kt` (workflow/execution in-memory store)
   - `ExecutionStateMachine.kt` (runtime transitions and projection)
   - `ExecutionScheduler.kt` (deduplicated scheduling signal loop)
   - `ExecutionRuntime.kt` (runtime state model)
4. **Task Abstraction (`TaskContracts.kt`)**
   - handler interface + outcome protocol
   - registry for dynamic task type lookup
   - fail-fast duplicate/blank handler type protection
5. **Built-in Task Types (`tasks/*.kt`)**
   - `http`
   - `script`
   - `approval`
   - `set_state`
   - `assert_state`
   - Each handler lives in its own class/file and is discovered via Spring component scanning (`@Component` + `List<TaskHandler>` injection).
6. **Bootstrap/Bean Wiring (`Application.kt`, `TaskForgeConfiguration.kt`)**
   - Spring Boot startup
   - Json/registry/service bean construction
   - startup validation that required built-in task handlers are present
7. **Cross-cutting Observability (`aop/ObservedOperationAspect.kt`)**
   - AOP-based request operation logging
   - Micrometer counters and latency timers on annotated API operations
8. **Request Idempotency (`IdempotencyService.kt`)**
   - endpoint-level idempotency for execution mutations (`start`, `cancel`, `approval`)
   - replay support for duplicate identical requests via `Idempotency-Key`
   - conflict protection when a key is reused for a different request payload

## 2) Extensibility Mechanism (key requirement)

The engine depends only on `TaskHandlerRegistry` + `TaskHandler`.

```kotlin
interface TaskHandler {
  val type: String
  suspend fun execute(input: TaskExecutionInput): TaskExecutionOutcome
}
```

ExecutionService resolves handlers by `task.type` at runtime.  
Adding a new task type only requires registering a new handler instance; engine source changes are not required.

This is verified by test:  
`ExecutionServiceTest.kt` -> `allows registering a new task type without engine changes`.

## 3) Workflow Definition Model

`POST /workflows` request:

```json
{
  "id": "optional-workflow-id",
  "name": "deploy-pipeline",
  "tasks": [
    {
      "id": "build",
      "type": "script",
      "dependencies": [],
      "condition": null,
      "timeoutMs": 30000,
      "retryPolicy": { "maxRetries": 2, "backoffMs": 500 },
      "config": { "command": "echo build" }
    }
  ]
}
```

Validation rules:

- unique task ids
- known task types (must exist in registry)
- no dangling dependencies
- no dependency cycles

Validation errors include `field`, `taskId`, and message for actionable debugging.

## 4) Execution Model

### Task statuses

- `PENDING`
- `RUNNING`
- `WAITING_APPROVAL`
- `SUCCEEDED`
- `FAILED`
- `TIMED_OUT`
- `SKIPPED_CONDITION`
- `SKIPPED_UPSTREAM`
- `CANCELLED`

### Execution statuses

- `RUNNING`
- `WAITING_APPROVAL`
- `CANCELLING`
- `SUCCEEDED`
- `FAILED`
- `CANCELLED`

### Scheduler behavior

- tasks start when all dependencies are terminal
- tasks with no unmet dependencies can launch concurrently
- if any dependency is failed/timed-out/cancelled/skipped-upstream, downstream task becomes `SKIPPED_UPSTREAM`
- if condition evaluates false, task becomes `SKIPPED_CONDITION`

## 5) State Flow and Reference Syntax

Execution state is a JSON document:

- starts from `initialState`
- each task output is written to `tasks.<taskId>`
- handlers can also patch arbitrary root paths (`statePatch`)

Reference syntax in task configs:

- `${path.to.value}` for full value replacement
- inline interpolation inside strings also supported

Example:

```json
{
  "values": {
    "deploy.image": "${tasks.build.imageTag}"
  }
}
```

If a reference does not resolve, config rendering fails and task fails with a non-retryable error.

## 6) Conditions

`condition` can be:

1. boolean primitive (`true` / `false`)
2. string primitive (`"true"` / `"false"`)
3. object comparator:
   - `{ "ref": "path.to.value", "exists": true }`
   - `{ "ref": "path.to.value", "equals": <json> }`
   - `{ "ref": "path.to.value", "notEquals": <json> }`

## 7) Retries and Timeouts

- each task can define `retryPolicy.maxRetries` + `retryPolicy.backoffMs`
- retry occurs only when handler returns `Failure(retryable = true)`
- timeout is enforced with coroutine `withTimeout`
- timeout final state is `TIMED_OUT` if retries are exhausted

Reliability note:

- `script` handler actively terminates the child process if coroutine is cancelled, ensuring timeout/cancel is not best-effort only.

## 8) Cancellation

`POST /executions/{id}/cancel`

- marks execution as cancelling
- pending and waiting-approval tasks are marked cancelled
- running tasks receive coroutine cancellation
- grace window waits for cooperative stop before forced cancellation

## 9) Approvals

Approval is modeled as a dedicated task type (`approval`) that returns `AwaitApproval`.

Config fields:

- `timeoutMs`
- `requiredIdentity` (optional)
- `prompt` (optional)
- `onTimeout`: `APPROVE | REJECT | FAIL`

Resolution endpoint:

`POST /executions/{id}/approvals/{taskId}`

```json
{
  "approved": true,
  "actor": "ops-lead",
  "comment": "looks good"
}
```

Identity is enforced when `requiredIdentity` is present.

## 10) Task Types

### Required

1. **http**
   - Performs outbound HTTP request
   - success: 2xx by default, or `allowedStatuses` override
   - non-2xx failure retryability: 5xx/429 retryable; others permanent
2. **script**
   - Runs shell command
   - success: exit code `0`
   - stderr alone is not failure if exit code is `0`

### Additional

3. **approval**
   - human gate + timeout policy
4. **set_state**
   - writes configured values into shared execution state
5. **assert_state** (extra)
   - asserts state existence/value before continuing

## 11) API Endpoints

1. `POST /workflows`
2. `GET /workflows/{workflowId}`
3. `POST /executions`
4. `GET /executions/{executionId}`
5. `POST /executions/{executionId}/cancel`
6. `POST /executions/{executionId}/approvals/{taskId}`

Idempotency contract (required header on mutating execution routes):

- `POST /executions` requires `Idempotency-Key`
- `POST /executions/{executionId}/cancel` requires `Idempotency-Key`
- `POST /executions/{executionId}/approvals/{taskId}` requires `Idempotency-Key`

Responses include `Idempotency-Replayed: true|false`.

Error envelope:

```json
{
  "message": "Workflow validation failed",
  "validationErrors": [
    { "taskId": "build", "field": "tasks.type", "message": "Unknown task type 'xyz'" }
  ]
}
```
