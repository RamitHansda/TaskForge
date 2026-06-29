# DECISIONS

## 1) In-memory persistence (chosen for assignment scope)

Decision:
- Keep workflows/executions in concurrent in-memory maps.

Why:
- lets the implementation focus on orchestration semantics (DAG, retries, approval, cancellation) rather than DB setup.

Trade-off:
- state is not durable across process restarts.
- production version would replace repositories with persistent storage.

## 2) Handler registry for extensibility

Decision:
- Engine only knows `TaskHandlerRegistry` and handler contracts.

Why:
- directly satisfies the "new task type without engine code changes" requirement.

Trade-off:
- runtime-only validation of handler availability.

## 3) JSON state + JSON config interpolation

Decision:
- Use JSON as the shared state model and task config substrate.
- Reference syntax: `${path.to.value}`.

Why:
- works across heterogeneous task types.
- avoids hard-coding task-specific coupling into engine.

Trade-off:
- weaker compile-time guarantees compared with typed state objects.

## 4) Condition model supports both simple and structured forms

Decision:
- allow primitive booleans/strings and object comparators (`ref + equals/notEquals/exists`).

Why:
- easy for simple workflows while still expressive enough for real gating logic.

Trade-off:
- not a full expression language.

## 5) Retry semantics: handler-controlled retryability

Decision:
- task handler returns `Failure(retryable = true|false)`.
- engine owns retry loop and backoff.
- unhandled exceptions from task handlers are treated as permanent by default (`retryable = false`).

Why:
- clean separation: handlers know error nature; engine knows policy.

Trade-off:
- requires handler authors to classify failures correctly.
- transient failures that are thrown (instead of returned as retryable outcomes) will not be retried.

## 6) Timeout enforcement via coroutine cancellation + process kill for scripts

Decision:
- use `withTimeout` at engine level.
- script handler terminates child process on cancellation.

Why:
- stronger timeout guarantees than passive best-effort waiting.

Trade-off:
- still bounded by OS process semantics for stubborn child processes.

## 7) Approval implemented as task type, not global special case

Decision:
- approval is modeled as a handler outcome (`AwaitApproval`) consumed by generic scheduler logic.

Why:
- keeps approval behavior composable with DAG semantics and task-level status tracking.

Trade-off:
- adds runtime bookkeeping for pending approvals + timeout jobs.

## 8) Failure propagation policy

Decision:
- downstream tasks become `SKIPPED_UPSTREAM` if any dependency terminally fails/times out/cancels/skips-upstream.
- tasks skipped by condition do not automatically fail dependents.

Why:
- preserves branch isolation and explicit state semantics.

Trade-off:
- downstream tasks relying on outputs from conditionally skipped tasks can still fail at interpolation time.

## 9) API intentionally synchronous for mutating approval/cancel operations

Decision:
- approval resolve and cancellation mutate runtime state before returning.

Why:
- callers get immediate and predictable post-request execution snapshot.

Trade-off:
- route handlers may await internal locking work under load.

## 10) Spring web layer split into dedicated components

Decision:
- keep bootstrap, bean wiring, controller routes, and exception handling in separate classes.

Why:
- improves local reasoning and testability.
- follows standard Spring conventions expected in production codebases.

Trade-off:
- slightly more files/indirection for a small assignment-sized service.

## 11) AOP for cross-cutting API observability

Decision:
- use Spring AOP with explicit method annotations for API operation observability.
- keep orchestration/business transitions explicit in engine code.

Why:
- removes repetitive logging/timing code from each endpoint.
- keeps telemetry behavior consistent while preserving readability of business logic.

Trade-off:
- debugging method flow can be less direct due to proxy interception.

## 12) Deduplicated scheduler signaling per execution

Decision:
- replace fire-and-forget scheduling launches with a per-execution scheduler signal loop (`scheduleRequested` + `schedulerRunning`).

Why:
- avoids redundant concurrent scheduler coroutines and reduces lock contention during bursty state updates.
- preserves deterministic scheduling behavior when many task completions trigger rescheduling at once.

Trade-off:
- introduces additional runtime flags and control flow complexity inside the execution runtime state.

## 13) Engine decomposed into orchestration, state machine, scheduler, and store

Decision:
- split `ExecutionService` internals into dedicated components:
  - `ExecutionStore` for persistence access
  - `ExecutionStateMachine` for runtime transitions
  - `ExecutionScheduler` for scheduling loop concerns
  - `ExecutionRuntime` for runtime state model types

Why:
- reduces cognitive load in the service façade.
- clarifies change boundaries for concurrency behavior vs. transition logic.
- improves testability and maintainability for future production hardening.

Trade-off:
- more files and indirection for readers new to the codebase.

## 14) One class per built-in task handler

Decision:
- split built-in handlers into separate files/classes and register them via Spring component discovery.

Why:
- keeps handler ownership and tests focused.
- reduces merge conflicts in handler-heavy iterations.
- improves readability and extension velocity as new task types are added.

Trade-off:
- slightly more framework coupling compared with manual explicit list registration.

## 15) Fail-fast handler registry integrity

Decision:
- reject blank handler types and duplicate handler type registrations at registry construction/registration time.
- validate required built-in handler types at Spring startup.

Why:
- prevents ambiguous runtime behavior when two handlers claim the same task type.
- catches misconfiguration at boot rather than during workflow execution.

Trade-off:
- startup becomes stricter; intentionally replacing a built-in handler now requires explicit code changes.

## 16) Workflow definition invariants and id uniqueness

Decision:
- reject duplicate workflow IDs (`409 Conflict`) instead of silently overwriting.
- validate core invariants on workflow definitions: non-blank name, non-empty task list, and non-negative retry/backoff with positive timeout values.

Why:
- protects orchestration metadata integrity and improves API ergonomics with explicit contract failures.

Trade-off:
- stricter validation can reject previously accepted but weak definitions.

## 17) Snapshot projection under execution mutex

Decision:
- build `ExecutionView` snapshots under the runtime mutex.

Why:
- prevents torn reads while task states, approvals, status, and state JSON are being mutated concurrently.
- guarantees each returned execution snapshot reflects a single consistent point-in-time view.

Trade-off:
- snapshot generation now acquires the same lock used for state transitions, adding minor read-path contention under heavy polling.

## 18) Endpoint idempotency keys for execution mutations

Decision:
- require `Idempotency-Key` for:
  - `POST /executions`
  - `POST /executions/{executionId}/cancel`
  - `POST /executions/{executionId}/approvals/{taskId}`
- persist in-memory idempotency records with request fingerprint hashing and replay completed responses.

Why:
- makes client retries safe during network failures/timeouts.
- prevents accidental duplicate execution starts/cancellations/approval resolutions.
- provides explicit conflict semantics for key reuse with different payloads.

Trade-off:
- in-memory idempotency records are process-local and non-durable.
- production deployment would use shared durable storage for cross-instance idempotency guarantees.
