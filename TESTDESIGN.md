# TESTDESIGN

## Goals

The test suite targets the highest-risk orchestration behavior:

1. validation correctness (bad workflows rejected early)
2. runtime correctness (dependency order + state flow)
3. control-flow correctness (approval pauses/resumes)
4. resiliency behavior (retry semantics)
5. extensibility guarantee (new task type without engine edits)

## Test Scope

Current tests live in:
- `src/test/kotlin/com/taskforge/engine/ExecutionServiceTest.kt`
- `src/test/kotlin/com/taskforge/TaskForgeControllerIntegrationTest.kt`

### Covered scenarios

1. **Unknown task type validation**
   - ensures user gets actionable validation errors (`field`, `taskId`).

2. **Cycle detection**
   - verifies DAG cycle rejection.

3. **Dependency execution + state propagation**
   - checks upstream outputs can be consumed downstream via `${...}` references.

4. **Approval-gated workflows**
   - checks execution transitions to `WAITING_APPROVAL`
   - verifies correct continuation after API approval.

5. **Retries**
   - verifies retry policy and attempt accounting for transient failures.
   - verifies unhandled task exceptions are treated as permanent failures (no automatic retry).

6. **Extensibility seam**
   - registers a custom handler in test code and executes it with no engine source changes.

7. **API integration behavior**
   - workflow create/retrieve happy path
   - validation error envelope on bad definitions
   - not-found behavior for missing workflows
   - conflict behavior for approval identity mismatch
   - AOP-observed controller methods still preserve endpoint behavior
8. **Registry integrity behavior**
   - duplicate handler type registration is rejected
   - blank handler type registration is rejected
9. **Workflow invariants**
   - duplicate workflow IDs are rejected with conflict semantics
   - invalid timeout/retry policy values are rejected at validation time
10. **Execution snapshot consistency**
   - concurrent polling during task transitions returns structurally consistent snapshots
11. **HTTP idempotency behavior**
   - mutating execution endpoints require `Idempotency-Key`
   - duplicate key + same payload replays original response
   - duplicate key + different payload is rejected as conflict

## Notable testing techniques

- Polling helper (`awaitExecution`) for async scheduler state transitions.
- In-memory custom handlers for deterministic behavior in retry/extensibility tests.

## Suggested additional tests (next steps)

1. **Timeout behavior**
   - assert `TIMED_OUT` terminal state after retries are exhausted.
2. **Cancellation under active script task**
   - ensure running tasks are cancelled and pending tasks never start.
3. **Approval timeout policies**
   - verify `APPROVE`, `REJECT`, and `FAIL` paths.
4. **API-level contract tests**
   - route tests for response shape and HTTP status codes.
5. **Concurrent branch stress test**
   - multiple independent tasks writing state concurrently.
