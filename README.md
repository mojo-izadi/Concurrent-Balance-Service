# Concurrent Balance Service

A Java 21 and Spring Boot service for asynchronous credits, debits, transfers, and balance reads. PostgreSQL stores account balances and transaction records. Code is organized into controllers, services, domain models, repositories, DTOs, configuration, and background execution.

## Implemented behavior

- Submissions validate the amount and account references, insert one `PENDING` transaction row in a separate committed database transaction, and then dispatch its transaction ID to a managed virtual thread. Native `INSERT ... ON CONFLICT DO NOTHING` arbitrates reused IDs without modifying existing rows. A successful submission returns `202 Accepted`; finalization can complete after the response.
- Each application instance dispatches at most 10 transactions concurrently. Dispatch does not wait or enqueue extra work in memory: when capacity is full, the committed transaction stays `PENDING` until a later recovery scan can dispatch it. Repeated dispatches for an ID already running on that instance are ignored. Capacity rejection does not consume a finalization retry or mark the transaction failed.
- Each finalization attempt starts a fresh `READ COMMITTED` database transaction. It locks the transaction's account rows in ascending account-ID order using PostgreSQL `FOR NO KEY UPDATE`, then reads the transaction status directly from the database. If another balance writer holds an account lock, this attempt waits before checking the transaction status. Scalar queries avoid stale JPA entity values after a lock wait.
- Balance changes and the terminal transaction status commit together. Transfers use one transaction record and one database transaction. Insufficient funds commits `FAILED` with `INSUFFICIENT_BALANCE` without changing balances.
- Technical errors retry in fresh database transactions. After the configured retry budget, a separate guarded database transaction records `FAILED` with `FINALIZATION_ERROR`. If finalization cannot safely acquire the required locks or reach the database, the transaction remains `PENDING` for recovery.
- A scheduler invokes the recovery service, which finds old pending IDs and submits them for finalization. It does not create new transaction records. The age threshold is a candidate-selection heuristic; row locks guard against duplicate finalization.
- Transaction IDs are primary keys and cannot be reused. Account references are protected by foreign keys and transaction identity fields are immutable through the repository implementation, which only updates the outcome fields after insertion.

Balance reads return the committed database balance. Pending transactions do not reserve funds or affect the read result. Finalization order is not guaranteed to match submission order.

Duplicate IDs are rejected with `409`, including identical retries, changed payloads, and IDs of failed transactions. A failed transaction never becomes eligible for finalization again; a new attempt requires a new ID. Transfers to the same account are rejected with `400` before admission: they provide no useful financial effect and can hide a caller mistake.

Credits and transfer destinations use checked arithmetic. A result above `Long.MAX_VALUE` fails with `FINALIZATION_ERROR` and leaves all balances unchanged. Fractional JSON amounts are rejected rather than truncated.

Nonnegative balances are enforced by the domain rules and transaction finalization. There is no database check constraint on the account balance, by product decision.

## REST API

All routes use the `/api` prefix. Credit, debit, and transfer endpoints require a positive integral `amount` and a unique `transactionId`.

Interactive API documentation is available at [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html); the generated OpenAPI JSON is at `/v3/api-docs`.

### Credit

```http
POST /api/accounts/{accountId}/credit
Content-Type: application/json

{"transactionId":"credit-1001","amount":250}
```

### Debit

```http
POST /api/accounts/{accountId}/debit
Content-Type: application/json

{"transactionId":"debit-1002","amount":75}
```

### Transfer

```http
POST /api/transfers
Content-Type: application/json

{"transactionId":"transfer-1003","sourceAccountId":"A","destinationAccountId":"B","amount":40}
```

Accepted submissions respond with `202` and a body such as `{"transactionId":"credit-1001","message":"Transaction registered successfully"}`. This confirms registration, not financial completion. Invalid requests return `400`, an unknown account returns `404`, and a reused transaction ID returns `409`. Temporary database failures return `503`.

### Read a balance

```http
GET /api/accounts/A/balance
```

Example response: `{"accountId":"A","balance":10000}`. There is no transaction-status endpoint; transaction outcomes are stored in `transactions`.

## Local database and run

Install a Java 21 JDK and Docker with the Compose plugin. The Gradle wrapper downloads Gradle and dependencies on the first build.

```sh
docker compose up -d --wait --wait-timeout 180 postgres
./gradlew compileJava
./gradlew run
```

The service listens on port `8080`; PostgreSQL is available at `jdbc:postgresql://localhost:15432/concurrent_balance_service` with the development credentials in `compose.yaml`. Database tables and constraints are initialized from `src/main/resources/schema.sql`.

On startup, the schema initializer creates three development accounts if they do not already exist:

| Account ID | Starting balance |
| --- | ---: |
| `A` | 10,000 |
| `B` | 10,000 |
| `C` | 10,000 |

Seed insertion is idempotent and does not reset balances already stored in the database. Additional accounts can be provisioned with an `INSERT INTO accounts (account_id, balance) VALUES (...)` SQL statement.

The application supports `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, and `SERVER_PORT` environment variables. `.env.example` contains the optional Compose PostgreSQL overrides; Compose loads `.env` after it is copied from that example.

## Configuration

| Property | Environment variable | Default | Meaning |
| --- | --- | --- | --- |
| `balance.finalization.max-concurrent-transactions` | `BALANCE_FINALIZATION_MAX_CONCURRENT_TRANSACTIONS` | `10` | Maximum dispatched transactions per application instance; overflow stays pending for recovery. |
| `balance.finalization.max-retries` | `BALANCE_FINALIZATION_MAX_RETRIES` | `3` | Additional technical attempts per worker run (initial attempt is extra). |
| `balance.finalization.retry-delay` | `BALANCE_FINALIZATION_RETRY_DELAY` | `100ms` | Delay between technical attempts; the previous database transaction has rolled back before waiting. |
| `balance.recovery.fixed-delay` | `BALANCE_RECOVERY_FIXED_DELAY` | `5s` | Initial delay and delay between recovery scans. |
| `balance.recovery.minimum-pending-age` | `BALANCE_RECOVERY_MINIMUM_PENDING_AGE` | `5s` | Minimum age for a pending transaction to be selected. |

The Hikari pool defaults to 20 connections; set `DATABASE_POOL_SIZE` to change it. Every new pooled connection receives a PostgreSQL lock timeout of `5s` and statement timeout of `10s`. Override them with `DATABASE_LOCK_TIMEOUT` and `DATABASE_STATEMENT_TIMEOUT` using PostgreSQL duration values such as `5s`. Idle database transactions remain limited to 60 seconds, and connection acquisition waits up to 30 seconds.

The lock timeout applies separately to each lock acquisition, and the statement timeout applies separately to each SQL statement, including its lock wait. These timeouts do not set a deadline for processing an entire submitted transaction: finalizing a transfer can issue several SQL statements, finalization may retry, and failure handling uses another database transaction. A timed-out database transaction rolls back before the retry policy runs. See [PostgreSQL's timeout documentation](https://www.postgresql.org/docs/18/runtime-config-client.html#RUNTIME-CONFIG-CLIENT-STATEMENT).

Recovery scans only run at the configured interval and select transactions old enough to qualify. A newly free finalization slot does not trigger an immediate scan. Local dispatch deduplication avoids scheduling IDs already running; account locks and the fresh status check also prevent duplicate financial effects across application instances.

## Architecture

The project uses a layered structure with explicit database transaction boundaries:

- `controller`: REST endpoints, HTTP error mapping, and the scheduled recovery trigger (`RecoveryController`).
- `service`: service interfaces beside their implementations, the `DatabaseTransactionBoundary` contract, and service exceptions in `service/exception`.
- `domain`: account and transaction models, statuses, and balance rules.
- `repository`: the `BalanceRepository` contract, its JPA implementation, finalization snapshots, and Spring Data repositories. JPA entities and mapping live in `repository/entity` and `repository/mapper`.
- `dto`: HTTP request, response, and error records.
- `config`: Spring wiring, finalization properties, OpenAPI configuration, and the existing `SpringDatabaseTransactionBoundary` implementation.
- `execution`: the `JobExecutor` contract and virtual-thread executor.

Each service interface sits beside its implementation in `service`, using `X` / `XImpl` names so each pair sorts together. Each interface declares the same public methods as its implementation:

| Interface | Implementation | Responsibility |
| --- | --- | --- |
| `BalanceService` | `BalanceServiceImpl` | Expose credit, debit, transfer, and balance reads. |
| `TransactionInitializer` | `TransactionInitializerImpl` | Validate requests, commit pending work, then dispatch it. |
| `TransactionFinalizationService` | `TransactionFinalizationServiceImpl` | Finalize a transaction, retry technical failures, and record outcomes. |
| `TransactionRecoveryService` | `TransactionRecoveryServiceImpl` | Find and dispatch old pending transactions. |

Controllers use service interfaces. `RecoveryController` only triggers `TransactionRecoveryService`. New transaction finalization and recovery share the same finalization service and repository guards. `TransactionInitializer` dispatches new work only after its `PENDING` row commits, including when its caller has an existing database transaction.

`DatabaseTransactionBoundary` delegates to `SpringDatabaseTransactionBoundary`, which uses `REQUIRES_NEW` and `READ COMMITTED` for each finalization attempt and guarded failure handling. Admission starts a new database transaction through a repository method annotation. All handwritten persistence SQL runs through Spring Data JPA native queries; no `JdbcTemplate` or direct JDBC calls are used.

The domain remains plain Java. Services use repository and executor contracts, while persistence entities and mapping stay within `repository`. Six ArchUnit rules check domain independence, layer dependencies, persistence isolation, and the absence of JDBC during every test run.

PostgreSQL supplies durable admission, uniqueness, foreign keys, row locking, and atomic balance/outcome commits across database connections. This avoids a JVM-wide lock and permits independent accounts to progress separately. The tradeoffs are database round trips, serialization on busy accounts, and PostgreSQL-specific SQL. Ordered account locks prevent cycles between transfers; `FOR NO KEY UPDATE` also permits the key-share locks used by admission's foreign-key checks. External writers that use different lock orders can still cause deadlocks, which finalization treats as technical failures. See [PostgreSQL's lock compatibility documentation](https://www.postgresql.org/docs/current/explicit-locking.html#LOCKING-ROWS).

Docker Compose provides a reproducible development database; Testcontainers provides an isolated test database. Neither Redis nor Kafka is needed because the committed transaction row already serves as recoverable work.

## Tests

With Java 21 installed and Docker running:

```sh
./gradlew test
```

The suite starts PostgreSQL 18.6 in a temporary Testcontainers container. It does not use or reset the Compose database, and it does not silently skip integration tests when Docker is unavailable. Testcontainers downloads its images on first use. Test fixtures have unique IDs, and scheduled scans are delayed while tests trigger recovery explicitly.

| Challenge requirement | Coverage |
| --- | --- |
| Credit, debit, balance reads | Domain examples and real HTTP/application/database transaction |
| Insufficient funds and no overdraft | Domain rules; simultaneous debits and transfers where exactly one can succeed |
| Idempotency for all three transaction types | Sequential and simultaneous duplicate submissions; changed payloads; duplicate workers; pending, finalized, and failed IDs |
| Concurrent single-account updates | 1,000 workers with mixed credits/debits and an exact expected final balance |
| Concurrent multi-account transactions | 600 opposing transfers across three accounts; exact balances and conserved total |
| Independent account progress | A database-confirmed lock wait on one account while another account completes |
| Input validation | Nonpositive amounts, malformed/fractional/out-of-range JSON, missing/long IDs, unknown accounts, same-account transfers |
| Transfer atomicity and failure | Rollback between writes; real database failure after both writes; successful retry without double effects |
| Durable admission and recovery | Commit before dispatch despite caller rollback; rejected initial dispatch; recovery without new transaction rows |
| Retry and finalization safety | Fresh database transactions, exhausted/nonretryable errors, interruption, unavailable finalization, and a competing successful worker |
| Layer boundaries | Automated rules for domain independence, service/controller/repository dependencies, persistence isolation, and absence of JDBC |

Reports are generated at `build/reports/tests/test/index.html` and `build/reports/jacoco/test/html/index.html`; JaCoCo XML is also generated. See [REVIEW.md](REVIEW.md) for the review findings and remaining limitations.

## Limitations

Admission is asynchronous even through the Java `BalanceService` interface: returning from `credit`, `debit`, or `transfer` does not guarantee that a subsequent balance read includes the transaction. No transaction-status HTTP endpoint, funds reservation, durable retry counter, or account provisioning endpoint is included.

Transaction finalization is bounded, but the recovery scan still loads all stale pending IDs in one query. A very large backlog would benefit from batched scanning. Sustained arrivals can outpace completion and grow the durable pending backlog. The concurrency limit is per application instance and does not guarantee fairness between busy and independent accounts. Recovery makes progress when the application/database are available and account-level database transactions complete; the age setting is not a completion guarantee.

`schema.sql` initializes fresh schemas; it is not a migration system. Future changes to existing tables would need explicit migrations. Development accounts are seeded on every startup if absent. A production deployment should separate seed data from schema management.

## Build artifacts

```sh
./gradlew build
java -jar build/libs/concurrent-balance-service-0.0.1-SNAPSHOT.jar
```

The application can also run with `./gradlew run`. For an existing database created before the transaction table rename, apply the SQL migrations in version order before starting the updated application. V2 upgrades the older schema; V3 renames the table to `transactions`, changes terminal statuses and failure reasons to finalization terms, and preserves all transaction rows. For the current Compose database, run `docker compose exec -T postgres psql -U concurrent_balance_service -d concurrent_balance_service -v ON_ERROR_STOP=1 < src/main/resources/db/migration/V3__transactions_table_and_finalization_status.sql`. `schema.sql` defines the names used for fresh databases. Stop local dependencies with `docker compose down`; named database data is preserved.
