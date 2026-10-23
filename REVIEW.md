# Challenge review

Reviewed against all requirements and testing sections of `Coding Challenge.pdf`.

## Result

The required operations, validation, duplicate protection, concurrent balance correctness, independent-account progress, and transfer atomicity are implemented and covered by executable tests. The design accepts work asynchronously; tests wait for its terminal status before asserting the final balance. The PDF does not prescribe a duplicate-response policy: this implementation rejects a reused ID without changing its original operation or financial effect.

The README maps each requirement to its tests and explains the same-account policy, concurrency strategy, idempotency, atomicity, deadlock handling, infrastructure tradeoffs, and build/test prerequisites.

## Corrections made

- **Duplicate admission could rewrite existing operations.** `saveAndFlush` uses merge semantics for assigned IDs. An existing operation could be overwritten or reset to `PENDING`, permitting another financial effect. Admission now uses an insert-only native query and rejects a conflict. Tests cover every operation type and pending, finished, failed, identical, changed, and concurrent submissions.
- **Overflow could produce invalid balances.** Credit and transfer additions now use `Math.addExact`. Overflow is nonretryable and commits a failure without financial changes. Nonnegative balances are enforced by the domain and transactional execution; the optional database balance constraint was removed at the user's explicit request.
- **Admission could block behind balance execution.** `FOR UPDATE` conflicts with the key-share locks needed by foreign-key checks, adding contention and potential lock cycles during transfer admission. A regression test reproduced the blocked admission. Ordered `FOR NO KEY UPDATE` locks preserve writer exclusion while allowing admission to complete.
- **Fractional HTTP amounts could be truncated.** Jackson's float-to-integer coercion is disabled and tested.
- **Admission relied on an implicit commit.** The adapter now uses `REQUIRES_NEW`, so dispatch always follows committed admission even when the caller owns a transaction that later rolls back.
- **The scheduler owned application orchestration.** Recovery selection and dispatch now live in `OperationRecoveryService`; the scheduling adapter invokes that use case.
- **Tests were empty placeholders.** Domain, application, MVC, PostgreSQL concurrency/failure, real HTTP, executor, and architecture tests now run with the standard Gradle command. Empty test discovery is no longer allowed.
- **Dispatch had no bound.** Each application instance now admits at most 10 operations to its virtual-thread executor at once. Slots are acquired before starting a thread and released after completion, failure, or dispatch rejection. There is no executor backlog; work that cannot be dispatched remains pending in the database. Repeated dispatches of an ID already running locally are ignored.
- **Lock and statement waits had no explicit limits.** Each pooled PostgreSQL connection now receives a 5-second lock timeout and a 10-second statement timeout. Timed-out attempts use the existing rollback/retry/finalization policy. These limits are configurable through environment variables.

## Boundaries and simplification

The domain depends only on Java and domain types. The application depends only on Java, domain types, and application-owned ports. Controllers invoke application services, and persistence stays behind `BalanceRepository`. Spring transactions and executor wiring remain in the outer layers. No violations were found under the six ArchUnit rules in `ArchitectureTest`.

All former JDBC queries now use Spring Data JPA native queries. Scalar balance/status reads avoid persistence-context staleness, removing the refresh workaround and direct `EntityManager` dependency from the adapter. Native guarded outcome updates modify only pending rows. Spring Data repositories expose only the methods needed by the adapter, so accidental merge-based admission is no longer available. Redundant validation, mapper methods, entity mutation methods, an unused mapper, and manual sleep-duration conversion were removed.

## Remaining issues and tradeoffs

1. **Recovery selection still needs batching for very large backlogs.** Dispatch is bounded and locally deduplicated, but each scan loads all stale pending IDs. Sustained overload can grow the database backlog, and delayed work waits for another scheduled scan. The limit applies per application instance and does not guarantee account fairness.
2. **Timeouts are not an operation deadline.** Lock and statement timeouts apply separately to individual database waits/statements. Operations can run longer through multiple statements, retries, connection acquisition, and guarded finalization. Connection acquisition still permits a 30-second wait; there is no overall job deadline.
3. **Retry accounting is per worker run.** If failure finalization cannot commit and the operation stays pending, recovery can start another run with a fresh retry budget. A strict lifetime attempt cap would require durable retry accounting.
4. **Future schema upgrades are manual.** `CREATE TABLE IF NOT EXISTS` does not alter existing tables. Future schema changes would need explicit migrations.
5. **Operational features remain outside scope.** There is no authentication, account provisioning API, retention policy for operation IDs, or production seed-data separation. The challenge does not require these. Deleting old operation IDs would weaken the current permanent duplicate-protection guarantee.

## Accepted product decisions

- Completion remains asynchronous, with no HTTP status endpoint: `202` and the Java service's return acknowledge registration.
- Exhausting the retry budget records `FAILED/EXECUTION_ERROR` when finalization succeeds. Failed operations are terminal and are not recovered.
- There is no nonnegative-balance database constraint. The application owns that rule.

No additional test cases were added for the dispatch/timeout/constraint follow-up, as requested. The existing executor tests were adapted to the constructor, and the assertion requiring the removed constraint was deleted.

The suite uses genuine PostgreSQL connections and synchronized workers, including database-observed lock waits. It is a correctness suite, not a throughput benchmark or an exhaustive process-crash/network-partition test. Recovery is exercised by leaving committed work undispatched and then running the recovery use case.
