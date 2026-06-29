# TaskForge Kotlin Backend

Kotlin implementation of the TaskForge orchestration engine assignment.

## Stack

- Kotlin + Coroutines
- Spring Boot (HTTP API)
- In-memory repositories/runtime state
- JUnit 5 tests

## Run

```bash
./mvnw spring-boot:run
```

## Test

```bash
mvn test
```

or with wrapper:

```bash
./mvnw test
```

## API Summary

- `POST /workflows` - register workflow definition
- `GET /workflows/{workflowId}` - retrieve workflow definition
- `POST /executions` - start execution (**requires `Idempotency-Key` header**)
- `GET /executions/{executionId}` - fetch execution state
- `POST /executions/{executionId}/cancel` - cancel execution (**requires `Idempotency-Key` header**)
- `POST /executions/{executionId}/approvals/{taskId}` - resolve approval task (**requires `Idempotency-Key` header**)

See `ARCHITECTURE.md` for request/response shapes and behavioral details.
