# DECISIONS

## 1) In-memory persistence for assignment scope

Decision:
- Store workflow definitions and execution runtimes in concurrent in-memory maps.

Why:
- Keeps implementation effort focused on orchestration semantics (DAG scheduling, retries, approvals, cancellation).

Trade-off:
- State is not durable across process restarts.
- Production deployment should replace this with persistent/shared storage.

## 2) Extensibility via handler contract + registry

Decision:
- Keep the engine generic and task-type-agnostic through:
  - `TaskHandler` interface
  - `TaskHandlerRegistry` lookup by task type

Why:
- New task types can be added without modifying engine scheduling/state logic.

Trade-off:
- Handler availability is validated at runtime/startup, not compile time.

## 3) Default task handlers as independent components

Decision:
- Implement each default task type as its own class (`http`, `script`, `approval`, `set_state`, `assert_state`) and register with Spring component discovery.

Why:
- Keeps handler ownership and behavior isolated.
- Improves readability and maintainability as task types grow.

Trade-off:
- Slightly tighter framework coupling to Spring component wiring.

## 4) JSON state model + template interpolation

Decision:
- Use JSON as shared workflow state and task config payload substrate.
- Support template references with `${path.to.value}`.

Why:
- Works across heterogeneous task types without over-constraining handler implementations.

Trade-off:
- Fewer compile-time guarantees than strongly typed state objects.

## 5) Condition model

Decision:
- Support both simple and structured conditions:
  - primitive boolean/string
  - object comparator (`ref` + `exists`/`equals`/`notEquals`)

Why:
- Keeps common gating cases simple while supporting practical branching checks.

Trade-off:
- Not a full expression language.

## 6) Retry policy semantics

Decision:
- Task handlers classify business/transport failures via `Failure(retryable = true|false)`.
- Engine owns attempt counting and backoff scheduling.
- Unhandled exceptions thrown from handlers are treated as permanent by default (`retryable = false`).

Why:
- Separates concerns cleanly: handlers classify error type; engine applies retry policy.

Trade-off:
- Handler authors must classify failures correctly.
- Transient failures that are thrown (instead of returned as retryable) will not be retried.

## 7) Timeout enforcement

Decision:
- Enforce per-task timeouts with coroutine `withTimeout`.
- For script tasks, terminate underlying OS process on cancellation.

Why:
- Provides active timeout enforcement instead of passive/best-effort waiting.

Trade-off:
- Final process termination still depends on OS process behavior for stubborn children.

## 8) Approval as a first-class task type

Decision:
- Model approval as a task outcome (`AwaitApproval`) inside the same task lifecycle model.
- Track approval wait state + timeout action as runtime metadata.

Why:
- Keeps approvals composable within DAG semantics and task-level observability.

Trade-off:
- Requires extra runtime bookkeeping (`pendingApprovals`, timeout jobs).

## 9) Failure propagation in DAG

Decision:
- If a dependency ends in FAILED/TIMED_OUT/CANCELLED/SKIPPED_UPSTREAM, dependent tasks become `SKIPPED_UPSTREAM`.
- Condition-skipped tasks (`SKIPPED_CONDITION`) do not automatically fail dependents.

Why:
- Preserves explicit branch semantics and predictable downstream behavior.

Trade-off:
- Downstream tasks that assume outputs from conditionally skipped tasks may fail during template resolution.

## 10) Scheduling model

Decision:
- Use a per-execution deduplicated scheduling signal loop (`scheduleRequested` + `schedulerRunning`).

Why:
- Avoids redundant concurrent scheduler scans during bursty updates.
- Preserves deterministic, low-contention readiness scanning.

Trade-off:
- Adds coordination flags to runtime control flow.

## 11) Runtime structure and responsibility boundaries

Decision:
- Separate engine responsibilities across focused components:
  - `ExecutionService` (orchestration facade)
  - `ExecutionStateMachine` (state transitions)
  - `ExecutionScheduler` (signal loop)
  - `ExecutionStore` (runtime/workflow storage)
  - `ExecutionRuntime` (mutable execution model)

Why:
- Improves local reasoning, testability, and maintainability.

Trade-off:
- More files/indirection compared to a single monolithic service.

## 12) Consistent execution snapshots

Decision:
- Build `ExecutionView` snapshots under the execution mutex.

Why:
- Prevents torn reads while tasks/status/state mutate concurrently.

Trade-off:
- Snapshot reads contend on the same lock as transition writes under heavy polling.

## 13) API structure

Decision:
- Keep bootstrapping, configuration, controller routes, and exception mapping in separate classes.
- Keep mutating operations (`cancel`, `resolve approval`) synchronous from API perspective (return updated snapshot).

Why:
- Aligns with common production Spring conventions and improves clarity.

Trade-off:
- Slightly more wiring/boilerplate for assignment scale.

## 14) Cross-cutting observability via AOP

Decision:
- Use `@ObservedOperation` + aspect interception for operation logs and Micrometer metrics.

Why:
- Avoids repetitive instrumentation code in each endpoint while preserving consistent telemetry tags.

Trade-off:
- Adds proxy/interception indirection to control flow.

## 15) Fail-fast registry and workflow validation

Decision:
- Reject duplicate/blank handler types.
- Validate required default handler types at startup.
- Validate workflow invariants at creation:
  - unique workflow id
  - non-blank workflow name
  - non-empty task list
  - valid timeout/retry settings

Why:
- Fails fast with actionable errors and protects runtime integrity.

Trade-off:
- Stricter contracts may reject loosely formed requests that were previously tolerated.

## 16) Endpoint idempotency for execution mutations

Decision:
- Require `Idempotency-Key` on:
  - `POST /executions`
  - `POST /executions/{executionId}/cancel`
  - `POST /executions/{executionId}/approvals/{taskId}`
- Store request fingerprints and replay completed responses for identical retries.

Why:
- Makes client retries safe under network failures/timeouts and prevents accidental duplicate mutations.

Trade-off:
- In-memory idempotency storage is process-local and non-durable.
- Production deployments should use shared durable idempotency storage.
