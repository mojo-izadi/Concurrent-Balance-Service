package com.example.concurrentbalanceservice.adapters.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

import com.example.concurrentbalanceservice.adapters.persistence.entity.AccountJpaEntity;
import com.example.concurrentbalanceservice.application.exception.AccountNotFoundException;
import com.example.concurrentbalanceservice.application.exception.DuplicateTransactionIdException;
import com.example.concurrentbalanceservice.application.exception.OperationAlreadyFinalizedException;
import com.example.concurrentbalanceservice.application.exception.OperationNotFoundException;
import com.example.concurrentbalanceservice.application.port.JobExecutor;
import com.example.concurrentbalanceservice.application.port.BalanceRepository;
import com.example.concurrentbalanceservice.application.service.OperationExecutionService;
import com.example.concurrentbalanceservice.application.service.TransactionInitializer;
import com.example.concurrentbalanceservice.domain.OperationStatus;
import com.example.concurrentbalanceservice.domain.OperationType;
import com.example.concurrentbalanceservice.domain.Transaction;
import com.example.concurrentbalanceservice.support.PostgreSQLIntegrationSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.IllegalTransactionStateException;

@Timeout(90)
class JpaBalanceRepositoryIntegrationTest extends PostgreSQLIntegrationSupport {
	private static final Instant NOW = Instant.now().minusSeconds(7_200).truncatedTo(java.time.temporal.ChronoUnit.MICROS);

	@ParameterizedTest
	@EnumSource(OperationType.class)
	void duplicateIdsNeverResetPendingOrFinishedOperationsEvenWithDifferentPayloads(OperationType type) {
		String source = newAccount(1_000);
		String destination = newAccount(500);
		Transaction original = pending(type, 100, source, destination);
		repository.insertPending(original);
		assertThatThrownBy(() -> repository.insertPending(original)).isInstanceOf(DuplicateTransactionIdException.class);
		var different = Transaction.pending(original.transactionId(), OperationType.CREDIT, 999, null, source, NOW.plusSeconds(1));
		assertThatThrownBy(() -> repository.insertPending(different)).isInstanceOf(DuplicateTransactionIdException.class);
		assertThat(storedTransaction(original.transactionId())).isEqualTo(original);
		executionService.execute(original.transactionId());
		Transaction finished = storedTransaction(original.transactionId());
		assertThat(finished.status()).isEqualTo(OperationStatus.FINISHED);
		for (int i = 0; i < 3; i++) {
			assertThatThrownBy(() -> repository.insertPending(original)).isInstanceOf(DuplicateTransactionIdException.class);
			executionService.execute(original.transactionId());
		}
		assertThat(storedTransaction(original.transactionId())).isEqualTo(finished);
		assertThat(operationCount(original.transactionId())).isEqualTo(1);
		assertBalances(type, source, destination);
	}

	@ParameterizedTest
	@EnumSource(OperationType.class)
	void simultaneousDuplicateSubmissionsAdmitAndApplyExactlyOnce(OperationType type) throws Exception {
		String source = newAccount(1_000);
		String destination = newAccount(500);
		String id = newId();
		var admitted = new AtomicInteger();
		var duplicates = new AtomicInteger();
		var initializer = new TransactionInitializer(repository, executionService::execute, Clock.systemUTC());
		runConcurrently(40, index -> {
			try {
				submit(initializer, type, source, destination, 100, id);
				admitted.incrementAndGet();
			}
			catch (DuplicateTransactionIdException exception) {
				duplicates.incrementAndGet();
			}
		});
		assertThat(admitted.get()).isEqualTo(1);
		assertThat(duplicates.get()).isEqualTo(39);
		assertThat(operationCount(id)).isEqualTo(1);
		assertThat(storedTransaction(id).status()).isEqualTo(OperationStatus.FINISHED);
		assertBalances(type, source, destination);
	}

	@Test
	void duplicateWorkersApplyTheSamePendingOperationOnlyOnce() throws Exception {
		String account = newAccount(1_000);
		Transaction transaction = pending(OperationType.CREDIT, 100, null, account);
		repository.insertPending(transaction);
		runConcurrently(40, ignored -> executionService.execute(transaction.transactionId()));
		assertThat(repository.getBalance(account)).isEqualTo(1_100);
		assertThat(storedTransaction(transaction.transactionId()).status()).isEqualTo(OperationStatus.FINISHED);
	}

	@Test
	void oneThousandConcurrentOperationsOnOneAccountHaveNoLostUpdates() throws Exception {
		String account = newAccount(100_000);
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 1_000; i++) {
			Transaction transaction = pending(i % 2 == 0 ? OperationType.CREDIT : OperationType.DEBIT,
					i % 2 == 0 ? 3 : 2, account, account);
			repository.insertPending(transaction);
			ids.add(transaction.transactionId());
		}
		runConcurrently(ids.size(), index -> executionService.execute(ids.get(index)));
		assertThat(repository.getBalance(account)).isEqualTo(100_500);
		assertThat(ids).allSatisfy(id -> assertThat(storedTransaction(id).status()).isEqualTo(OperationStatus.FINISHED));
	}

	@Test
	void concurrentOpposingTransfersAcrossSeveralAccountsConserveMoney() throws Exception {
		String a = newAccount(10_000);
		String b = newAccount(10_000);
		String c = newAccount(10_000);
		String[][] directions = {{a, b}, {b, a}, {b, c}, {c, b}, {c, a}, {a, c}};
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 600; i++) {
			String[] direction = directions[i % directions.length];
			Transaction transaction = pending(OperationType.TRANSFER, 1, direction[0], direction[1]);
			repository.insertPending(transaction);
			ids.add(transaction.transactionId());
		}
		runConcurrently(ids.size(), index -> executionService.execute(ids.get(index)));
		assertThat(repository.getBalance(a)).isEqualTo(10_000);
		assertThat(repository.getBalance(b)).isEqualTo(10_000);
		assertThat(repository.getBalance(c)).isEqualTo(10_000);
		assertThat(ids).allSatisfy(id -> assertThat(storedTransaction(id).status()).isEqualTo(OperationStatus.FINISHED));
	}

	@ParameterizedTest
	@EnumSource(value = OperationType.class, names = {"DEBIT", "TRANSFER"})
	void competingWithdrawalsCannotOverdrawTheSource(OperationType type) throws Exception {
		String source = newAccount(1_000);
		String destination = newAccount(500);
		var first = pending(type, 700, source, destination);
		var second = pending(type, 700, source, destination);
		repository.insertPending(first);
		repository.insertPending(second);
		var ids = List.of(first.transactionId(), second.transactionId());
		runConcurrently(2, index -> executionService.execute(ids.get(index)));
		assertThat(repository.getBalance(source)).isEqualTo(300);
		assertThat(repository.getBalance(destination)).isEqualTo(type == OperationType.TRANSFER ? 1_200 : 500);
		assertThat(ids.stream().map(this::storedTransaction).map(Transaction::status))
				.containsExactlyInAnyOrder(OperationStatus.FINISHED, OperationStatus.FAILED);
		assertThat(ids.stream().map(this::storedTransaction).filter(t -> t.status() == OperationStatus.FAILED))
				.allSatisfy(t -> assertThat(t.failureReason()).isEqualTo("INSUFFICIENT_BALANCE"));
	}

	@Test
	void failedIdsCannotBeReusedAfterFundsBecomeAvailable() {
		String account = newAccount(0);
		var transaction = pending(OperationType.DEBIT, 100, account, null);
		repository.insertPending(transaction);
		executionService.execute(transaction.transactionId());
		Transaction failed = storedTransaction(transaction.transactionId());
		assertThat(failed.failureReason()).isEqualTo("INSUFFICIENT_BALANCE");
		boundary.inNewTransaction(() -> { repository.updateBalance(account, 1_000); return null; });
		assertThatThrownBy(() -> repository.insertPending(transaction)).isInstanceOf(DuplicateTransactionIdException.class);
		executionService.execute(transaction.transactionId());
		assertThat(repository.getBalance(account)).isEqualTo(1_000);
		assertThat(storedTransaction(transaction.transactionId())).isEqualTo(failed);
	}

	@Test
	void admissionCommitsBeforeDispatchEvenIfTheCallersTransactionRollsBack() {
		String account = newAccount(1_000);
		String id = newId();
		JobExecutor dispatch = transactionId -> assertThat(operationCount(transactionId)).isEqualTo(1);
		var initializer = new TransactionInitializer(repository, dispatch, Clock.systemUTC());
		assertThatThrownBy(() -> boundary.inNewTransaction(() -> {
			initializer.credit(account, 100, id);
			throw new IllegalStateException("caller rollback");
		})).hasMessage("caller rollback");
		assertThat(operationCount(id)).isEqualTo(1);
		assertThat(storedTransaction(id).status()).isEqualTo(OperationStatus.PENDING);
		assertThat(repository.getBalance(account)).isEqualTo(1_000);
	}

	@Test
	void blockedWorkerRechecksStatusAfterTheWinningWorkerCommits() throws Exception {
		String account = newAccount(1_000);
		Transaction transaction = pending(OperationType.CREDIT, 100, null, account);
		repository.insertPending(transaction);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var blocked = boundary.inNewTransaction(() -> {
				repository.lockForTransactionExecution(transaction.transactionId());
				var future = executor.submit(() -> executionService.execute(transaction.transactionId()));
				awaitBlockedWorker();
				repository.updateBalance(account, 1_100);
				repository.updateOutcome(transaction.transactionId(), OperationStatus.FINISHED, null, NOW);
				return future;
			});
			blocked.get(5, TimeUnit.SECONDS);
		}
		assertThat(repository.getBalance(account)).isEqualTo(1_100);
		assertThat(storedTransaction(transaction.transactionId()).status()).isEqualTo(OperationStatus.FINISHED);
	}

	@Test
	void independentAccountFinishesWhileAnotherWorkerIsWaitingOnALock() throws Exception {
		String a = newAccount(1_000);
		String b = newAccount(500);
		Transaction first = pending(OperationType.CREDIT, 100, null, a);
		Transaction second = pending(OperationType.CREDIT, 100, null, b);
		repository.insertPending(first);
		repository.insertPending(second);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var blocked = boundary.inNewTransaction(() -> {
				repository.lockForTransactionExecution(first.transactionId());
				var waiting = executor.submit(() -> executionService.execute(first.transactionId()));
				awaitBlockedWorker();
				var independent = executor.submit(() -> executionService.execute(second.transactionId()));
				assertThatCode(() -> independent.get(5, TimeUnit.SECONDS)).doesNotThrowAnyException();
				assertThat(waiting.isDone()).isFalse();
				assertThat(repository.getBalance(b)).isEqualTo(600);
				return waiting;
			});
			blocked.get(5, TimeUnit.SECONDS);
		}
		assertThat(repository.getBalance(a)).isEqualTo(1_100);
	}

	@Test
	void newAdmissionDoesNotWaitForAnExecutingTransfersAccountLocks() {
		String a = newAccount(1_000);
		String b = newAccount(500);
		var executing = pending(OperationType.TRANSFER, 100, a, b);
		var incoming = pending(OperationType.TRANSFER, 100, b, a);
		repository.insertPending(executing);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			boundary.inNewTransaction(() -> {
				repository.lockForTransactionExecution(executing.transactionId());
				var admission = executor.submit(() -> repository.insertPending(incoming));
				assertThatCode(() -> admission.get(3, TimeUnit.SECONDS)).doesNotThrowAnyException();
				return null;
			});
		}
		assertThat(operationCount(incoming.transactionId())).isEqualTo(1);
	}

	@Test
	void aTransientFailureRollsBackTheFirstAttemptBeforeRetrying() {
		String a = newAccount(1_000);
		String b = newAccount(500);
		var transfer = pending(OperationType.TRANSFER, 300, a, b);
		repository.insertPending(transfer);
		var failures = new AtomicInteger();
		var failing = mock(BalanceRepository.class, delegatesTo(repository));
		doAnswer(call -> {
			if (failures.getAndIncrement() == 0) throw new DataAccessResourceFailureException("transient error after balance writes");
			repository.updateOutcome(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
			return null;
		}).when(failing).updateOutcome(anyString(), any(), any(), any());
		new OperationExecutionService(failing, boundary, Clock.systemUTC(), 1, Duration.ZERO).execute(transfer.transactionId());
		assertThat(failures.get()).isEqualTo(2);
		assertThat(repository.getBalance(a)).isEqualTo(700);
		assertThat(repository.getBalance(b)).isEqualTo(800);
		assertThat(storedTransaction(transfer.transactionId()).status()).isEqualTo(OperationStatus.FINISHED);
	}

	@Test
	void failureFinalizationWaitingOnAWinningWorkerCannotOverwriteItsOutcome() throws Exception {
		String account = newAccount(1_000);
		var transaction = pending(OperationType.CREDIT, 100, null, account);
		repository.insertPending(transaction);
		var failing = mock(BalanceRepository.class, delegatesTo(repository));
		doThrow(new DataAccessResourceFailureException("first attempt failed"))
				.doAnswer(call -> repository.lockForTransactionExecution(transaction.transactionId()))
				.when(failing).lockForTransactionExecution(transaction.transactionId());
		var service = new OperationExecutionService(failing, boundary, Clock.systemUTC(), 0, Duration.ZERO);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var finalizer = boundary.inNewTransaction(() -> {
				repository.lockForTransactionExecution(transaction.transactionId());
				var future = executor.submit(() -> service.execute(transaction.transactionId()));
				awaitBlockedWorker();
				repository.updateBalance(account, 1_100);
				repository.updateOutcome(transaction.transactionId(), OperationStatus.FINISHED, null, NOW);
				return future;
			});
			finalizer.get(5, TimeUnit.SECONDS);
		}
		assertThat(repository.getBalance(account)).isEqualTo(1_100);
		assertThat(storedTransaction(transaction.transactionId()).status()).isEqualTo(OperationStatus.FINISHED);
	}

	@Test
	void scalarLockReadsBypassAnAlreadyCachedAccountEntity() {
		String account = newAccount(1_000);
		Transaction credit = pending(OperationType.CREDIT, 100, null, account);
		Transaction debit = pending(OperationType.DEBIT, 50, account, null);
		repository.insertPending(credit);
		repository.insertPending(debit);
		boundary.inNewTransaction(() -> {
			assertThat(entityManager.find(AccountJpaEntity.class, account).getBalance()).isEqualTo(1_000);
			try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
				assertThatCode(() -> executor.submit(() -> executionService.execute(credit.transactionId())).get(5, TimeUnit.SECONDS))
						.doesNotThrowAnyException();
			}
			var snapshot = repository.lockForTransactionExecution(debit.transactionId());
			assertThat(snapshot.lockedAccounts().get(account).balance()).isEqualTo(1_100);
			return null;
		});
	}

	@Test
	void anErrorBetweenTransferWritesRollsBackTheBalanceAndLeavesPendingWork() {
		String a = newAccount(1_000);
		String b = newAccount(500);
		var transfer = pending(OperationType.TRANSFER, 300, a, b);
		repository.insertPending(transfer);
		assertThatThrownBy(() -> boundary.inNewTransaction(() -> {
			repository.lockForTransactionExecution(transfer.transactionId());
			repository.updateBalance(a, 700);
			throw new IllegalStateException("interrupted transfer");
		})).hasMessage("interrupted transfer");
		assertThat(repository.getBalance(a)).isEqualTo(1_000);
		assertThat(repository.getBalance(b)).isEqualTo(500);
		assertThat(storedTransaction(transfer.transactionId()).status()).isEqualTo(OperationStatus.PENDING);
		executionService.execute(transfer.transactionId());
		assertThat(repository.getBalance(a)).isEqualTo(700);
		assertThat(repository.getBalance(b)).isEqualTo(800);
	}

	@Test
	void databaseFailureRecordingOutcomeRollsBackBothSidesOfATransfer() {
		String a = newAccount(1_000);
		String b = newAccount(500);
		var transfer = pending(OperationType.TRANSFER, 300, a, b);
		repository.insertPending(transfer);
		// A real database error after both balance writes tests atomicity through the JPA transaction manager.
		String constraint = "test_" + newId().replace("-", "");
		boundary.inNewTransaction(() -> entityManager.createNativeQuery("ALTER TABLE balance_operations ADD CONSTRAINT "
				+ constraint + " CHECK (transaction_id <> '" + transfer.transactionId() + "' OR status <> 'FINISHED')").executeUpdate());
		try {
			new OperationExecutionService(repository, boundary, Clock.systemUTC(), 0, Duration.ZERO).execute(transfer.transactionId());
			assertThat(repository.getBalance(a)).isEqualTo(1_000);
			assertThat(repository.getBalance(b)).isEqualTo(500);
			assertThat(storedTransaction(transfer.transactionId()).status()).isEqualTo(OperationStatus.FAILED);
			assertThat(storedTransaction(transfer.transactionId()).failureReason()).isEqualTo("EXECUTION_ERROR");
		}
		finally {
			boundary.inNewTransaction(() -> entityManager.createNativeQuery("ALTER TABLE balance_operations DROP CONSTRAINT " + constraint).executeUpdate());
		}
	}

	@ParameterizedTest
	@EnumSource(value = OperationType.class, names = {"CREDIT", "TRANSFER"})
	void overflowingBalancesFailWithoutChangingEitherAccount(OperationType type) {
		String a = newAccount(1_000);
		String b = newAccount(Long.MAX_VALUE);
		var transaction = pending(type, 1, a, b);
		repository.insertPending(transaction);
		executionService.execute(transaction.transactionId());
		assertThat(repository.getBalance(a)).isEqualTo(1_000);
		assertThat(repository.getBalance(b)).isEqualTo(Long.MAX_VALUE);
		assertThat(storedTransaction(transaction.transactionId()).status()).isEqualTo(OperationStatus.FAILED);
		assertThat(storedTransaction(transaction.transactionId()).failureReason()).isEqualTo("EXECUTION_ERROR");
	}

	@Test
	void recoveryQuerySelectsOnlyOldPendingRowsInStableOrder() {
		String account = newAccount(1_000);
		var first = pending(OperationType.CREDIT, 1, null, account);
		var second = Transaction.pending(newId(), OperationType.CREDIT, 1, null, account, NOW.plusSeconds(1));
		var finished = pending(OperationType.CREDIT, 1, null, account);
		var failed = pending(OperationType.DEBIT, 10_000, account, null);
		var recent = Transaction.pending(newId(), OperationType.CREDIT, 1, null, account, Instant.now());
		List.of(first, second, finished, failed, recent).forEach(repository::insertPending);
		executionService.execute(finished.transactionId());
		executionService.execute(failed.transactionId());
		assertThat(repository.findStalePendingTransactionIds(Duration.ofHours(1)))
				.containsSubsequence(first.transactionId(), second.transactionId())
				.doesNotContain(finished.transactionId(), failed.transactionId(), recent.transactionId());
	}

	@Test
	void persistenceRequiresExecutionTransactionsAndRejectsMissingRecords() {
		assertThatThrownBy(() -> repository.lockForTransactionExecution(newId())).isInstanceOf(IllegalTransactionStateException.class);
		assertThatThrownBy(() -> repository.updateBalance(newId(), 1)).isInstanceOf(IllegalTransactionStateException.class);
		assertThatThrownBy(() -> repository.getBalance(newId())).isInstanceOf(AccountNotFoundException.class);
		assertThatThrownBy(() -> boundary.inNewTransaction(() -> repository.lockForTransactionExecution(newId())))
				.isInstanceOf(OperationNotFoundException.class);
		assertThatThrownBy(() -> boundary.inNewTransaction(() -> { repository.updateBalance(newId(), 1); return null; }))
				.isInstanceOf(AccountNotFoundException.class);
		assertThatThrownBy(() -> boundary.inNewTransaction(() -> { repository.updateOutcome(newId(), OperationStatus.FINISHED, null, NOW); return null; }))
				.isInstanceOf(OperationNotFoundException.class);
	}

	@Test
	void terminalOutcomesCannotBeOverwritten() {
		var transaction = pending(OperationType.CREDIT, 100, null, newAccount(1_000));
		repository.insertPending(transaction);
		executionService.execute(transaction.transactionId());
		assertThatThrownBy(() -> boundary.inNewTransaction(() -> {
			repository.updateOutcome(transaction.transactionId(), OperationStatus.FAILED, "EXECUTION_ERROR", NOW);
			return null;
		})).isInstanceOf(OperationAlreadyFinalizedException.class);
		assertThat(storedTransaction(transaction.transactionId()).status()).isEqualTo(OperationStatus.FINISHED);
	}

	@Test
	void databaseConstraintsRejectUnknownAccounts() {
		var unknown = pending(OperationType.CREDIT, 1, null, newId());
		assertThatThrownBy(() -> repository.insertPending(unknown)).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(operationCount(unknown.transactionId())).isZero();
	}

	private void awaitBlockedWorker() {
		await().atMost(Duration.ofSeconds(5)).until(() -> boundary.inNewTransaction(() -> ((Number) entityManager.createNativeQuery("""
				SELECT COUNT(*) FROM pg_stat_activity
				WHERE datname = current_database() AND wait_event_type = 'Lock'
				AND query LIKE '%SELECT balance FROM accounts%'
				""").getSingleResult()).longValue()) > 0);
	}

	private Transaction pending(OperationType type, long amount, String source, String destination) {
		return Transaction.pending(newId(), type, amount, type == OperationType.CREDIT ? null : source,
				type == OperationType.DEBIT ? null : destination, NOW);
	}

	private void assertBalances(OperationType type, String source, String destination) {
		assertThat(repository.getBalance(source)).isEqualTo(type == OperationType.CREDIT ? 1_000 : 900);
		assertThat(repository.getBalance(destination)).isEqualTo(type == OperationType.DEBIT ? 500 : 600);
	}

	private void submit(TransactionInitializer initializer, OperationType type, String source, String destination, long amount, String id) {
		switch (type) {
			case CREDIT -> initializer.credit(destination, amount, id);
			case DEBIT -> initializer.debit(source, amount, id);
			case TRANSFER -> initializer.transfer(source, destination, amount, id);
		}
	}
}
