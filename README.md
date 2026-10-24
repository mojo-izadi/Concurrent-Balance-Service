# Concurrent Balance Service

A Java 21 service for credit, debit, transfer, and balance reads.

## Architecture

`BalanceController` calls `BalanceService`, which uses `TransactionInitializer` to validate requests and save a `PENDING` row through `JpaBalanceRepository`. Balance reads also go through the repository. The initializer submits the transaction ID to `VirtualThreadJobExecutor`. It runs `TransactionFinalizationService` on a virtual thread; that service locks accounts through the repository, uses the `Transaction` domain model to calculate the outcome, and commits balances and status together. `RecoveryController` schedules `TransactionRecoveryService` to find pending transactions through the repository and submit them to the same executor.

## Guarantees

- **Idempotency:** `transactionId` is unique in PostgreSQL. Reusing an ID is rejected, including simultaneous duplicate requests, so a transaction affects balances at most once.
- **Correctness:** Debits check the locked balance before subtracting, preventing overdrafts. Checked addition detects balance overflow.
- **Concurrency:** Finalization locks the affected account rows in a consistent order. Workers on the same account wait for one another; workers on independent accounts can proceed. Each attempt runs in a fresh database transaction.
- **Consistency:** A transfer subtracts and adds the same amount. Balance changes and the transaction's final status commit together, so a failed attempt cannot leave a partial transfer.
- **Recovery:** Accepted transactions remain stored for eventual processing. Workers wait or retry after temporary locks; the scheduled job picks up pending work after dispatch failures. Once the database and workers can make progress, each transaction reaches a final success or failure outcome.

## Product decisions

- Invalid requests are rejected, including zero or negative amounts and missing or malformed values.
- Requests for an unknown account are rejected.
- A transfer from an account to itself is rejected as an error.
- Requesting a transaction creates a `PENDING` record immediately. The returned acceptance confirms that registration; finalization happens afterward. Balance reads show committed balances.

## Infrastructure and dependencies

- **PostgreSQL** is the relational store for fast retrieval through indexes, durable transaction records, row locks, and atomic database transactions.
- **Spring's scheduled job** runs at fixed intervals to scan pending transactions and retry dispatch.
- **Java virtual threads** run transaction finalization tasks without tying up a platform thread per task.

Dependencies: Java 21 and the Gradle wrapper for building; PostgreSQL, Spring Boot, Spring Web MVC, Spring Data JPA, the PostgreSQL JDBC driver, and springdoc OpenAPI at runtime. Docker Compose supports local PostgreSQL. Tests require Docker, Testcontainers, JUnit 5, and Spring Boot's test libraries.
