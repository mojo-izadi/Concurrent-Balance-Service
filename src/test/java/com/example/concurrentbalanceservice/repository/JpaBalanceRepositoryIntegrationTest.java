package com.example.concurrentbalanceservice.repository;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.concurrentbalanceservice.domain.TransactionStatus;
import com.example.concurrentbalanceservice.domain.TransactionType;
import com.example.concurrentbalanceservice.domain.Transaction;
import com.example.concurrentbalanceservice.execution.JobExecutor;
import com.example.concurrentbalanceservice.repository.entity.AccountJpaEntity;
import com.example.concurrentbalanceservice.service.TransactionFinalizationServiceImpl;
import com.example.concurrentbalanceservice.service.TransactionInitializer;
import com.example.concurrentbalanceservice.service.TransactionInitializerImpl;
import com.example.concurrentbalanceservice.service.exception.AccountNotFoundException;
import com.example.concurrentbalanceservice.service.exception.DuplicateTransactionIdException;
import com.example.concurrentbalanceservice.service.exception.TransactionAlreadyFinalizedException;
import com.example.concurrentbalanceservice.service.exception.TransactionNotFoundException;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;

@Timeout(90)
class JpaBalanceRepositoryIntegrationTest extends PostgreSQLIntegrationSupport {
	private static final Instant NOW = Instant.now().minusSeconds(7_200).truncatedTo(java.time.temporal.ChronoUnit.MICROS);

	@ParameterizedTest
	@EnumSource(TransactionType.class)
	void duplicateIdsNeverResetPendingOrFinalizedTransactionsEvenWithDifferentPayloads(TransactionType type) {
		String source = newAccount(1_000);
		String destination = newAccount(500);
		Transaction original = pending(type, 100, source, destination);
		repository.insertPending(original);
		assertThatThrownBy(() -> repository.insertPending(original)).isInstanceOf(DuplicateTransactionIdException.class);
		var different = Transaction.pending(original.transactionId(), TransactionType.CREDIT, 999, null, source, NOW.plusSeconds(1));
		assertThatThrownBy(() -> repository.insertPending(different)).isInstanceOf(DuplicateTransactionIdException.class);
		assertThat(storedTransaction(original.transactionId())).isEqualTo(original);
		finalizationService.finalizeTransaction(original.transactionId());
		Transaction finalized = storedTransaction(original.transactionId());
		assertThat(finalized.status()).isEqualTo(TransactionStatus.FINALIZED);
		for (int i = 0; i < 3; i++) {
			assertThatThrownBy(() -> repository.insertPending(original)).isInstanceOf(DuplicateTransactionIdException.class);
			finalizationService.finalizeTransaction(original.transactionId());
		}
		assertThat(storedTransaction(original.transactionId())).isEqualTo(finalized);
		assertThat(transactionCount(original.transactionId())).isEqualTo(1);
		assertBalances(type, source, destination);
	}

	@ParameterizedTest
	@EnumSource(TransactionType.class)
	void simultaneousDuplicateSubmissionsAdmitAndApplyExactlyOnce(TransactionType type) throws Exception {
		String source = newAccount(1_000);
		String destination = newAccount(500);
		String id = newId();
		var admitted = new AtomicInteger();
		var duplicates = new AtomicInteger();
		var initializer = new TransactionInitializerImpl(repository, finalizationService::finalizeTransaction, Clock.systemUTC());
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
		assertThat(transactionCount(id)).isEqualTo(1);
		assertThat(storedTransaction(id).status()).isEqualTo(TransactionStatus.FINALIZED);
		assertBalances(type, source, destination);
	}

	@Test
	void duplicateWorkersApplyTheSamePendingTransactionOnlyOnce() throws Exception {
		String account = newAccount(1_000);
		Transaction transaction = pending(TransactionType.CREDIT, 100, null, account);
		repository.insertPending(transaction);
		runConcurrently(40, ignored -> finalizationService.finalizeTransaction(transaction.transactionId()));
		assertThat(repository.getBalance(account)).isEqualTo(1_100);
		assertThat(storedTransaction(transaction.transactionId()).status()).isEqualTo(TransactionStatus.FINALIZED);
	}

	@Test
	void oneThousandConcurrentTransactionsOnOneAccountHaveNoLostUpdates() throws Exception {
		String account = newAccount(100_000);
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 1_000; i++) {
			Transaction transaction = pending(i % 2 == 0 ? TransactionType.CREDIT : TransactionType.DEBIT,
					i % 2 == 0 ? 3 : 2, account, account);
			repository.insertPending(transaction);
			ids.add(transaction.transactionId());
		}
		runConcurrently(ids.size(), index -> finalizationService.finalizeTransaction(ids.get(index)));
		assertThat(repository.getBalance(account)).isEqualTo(100_500);
		assertThat(ids).allSatisfy(id -> assertThat(storedTransaction(id).status()).isEqualTo(TransactionStatus.FINALIZED));
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
			Transaction transaction = pending(TransactionType.TRANSFER, 1, direction[0], direction[1]);
			repository.insertPending(transaction);
			ids.add(transaction.transactionId());
		}
		runConcurrently(ids.size(), index -> finalizationService.finalizeTransaction(ids.get(index)));
		assertThat(repository.getBalance(a)).isEqualTo(10_000);
		assertThat(repository.getBalance(b)).isEqualTo(10_000);
		assertThat(repository.getBalance(c)).isEqualTo(10_000);
		assertThat(ids).allSatisfy(id -> assertThat(storedTransaction(id).status()).isEqualTo(TransactionStatus.FINALIZED));
	}

	@ParameterizedTest
	@EnumSource(value = TransactionType.class, names = {"DEBIT", "TRANSFER"})
	void competingWithdrawalsCannotOverdrawTheSource(TransactionType type) throws Exception {
		String source = newAccount(1_000);
		String destination = newAccount(500);
		var first = pending(type, 700, source, destination);
		var second = pending(type, 700, source, destination);
		repository.insertPending(first);
		repository.insertPending(second);
		var ids = List.of(first.transactionId(), second.transactionId());
		runConcurrently(2, index -> finalizationService.finalizeTransaction(ids.get(index)));
		assertThat(repository.getBalance(source)).isEqualTo(300);
		assertThat(repository.getBalance(destination)).isEqualTo(type == TransactionType.TRANSFER ? 1_200 : 500);
		assertThat(ids.stream().map(this::storedTransaction).map(Transaction::status))
				.containsExactlyInAnyOrder(TransactionStatus.FINALIZED, TransactionStatus.FAILED);
		assertThat(ids.stream().map(this::storedTransaction).filter(t -> t.status() == TransactionStatus.FAILED))
				.allSatisfy(t -> assertThat(t.failureReason()).isEqualTo("INSUFFICIENT_BALANCE"));
	}

	@Test
	void failedIdsCannotBeReusedAfterFundsBecomeAvailable() {
		String account = newAccount(0);
		var transaction = pending(TransactionType.DEBIT, 100, account, null);
		repository.insertPending(transaction);
		finalizationService.finalizeTransaction(transaction.transactionId());
		Transaction failed = storedTransaction(transaction.transactionId());
		assertThat(failed.failureReason()).isEqualTo("INSUFFICIENT_BALANCE");
		boundary.inNewDatabaseTransaction(() -> { repository.updateBalance(account, 1_000); return null; });
		assertThatThrownBy(() -> repository.insertPending(transaction)).isInstanceOf(DuplicateTransactionIdException.class);
		finalizationService.finalizeTransaction(transaction.transactionId());
		assertThat(repository.getBalance(account)).isEqualTo(1_000);
		assertThat(storedTransaction(transaction.transactionId())).isEqualTo(failed);
	}

	@Test
	void admissionCommitsBeforeDispatchEvenIfTheCallersTransactionRollsBack() {
		String account = newAccount(1_000);
		String id = newId();
		JobExecutor dispatch = transactionId -> assertThat(transactionCount(transactionId)).isEqualTo(1);
		var initializer = new TransactionInitializerImpl(repository, dispatch, Clock.systemUTC());
		assertThatThrownBy(() -> boundary.inNewDatabaseTransaction(() -> {
			initializer.credit(account, 100, id);
			throw new IllegalStateException("caller rollback");
		})).hasMessage("caller rollback");
		assertThat(transactionCount(id)).isEqualTo(1);
		assertThat(storedTransaction(id).status()).isEqualTo(TransactionStatus.PENDING);
		assertThat(repository.getBalance(account)).isEqualTo(1_000);
	}

	@Test
	void blockedWorkerRechecksStatusAfterTheWinningWorkerCommits() throws Exception {
		String account = newAccount(1_000);
		Transaction transaction = pending(TransactionType.CREDIT, 100, null, account);
		repository.insertPending(transaction);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var blocked = boundary.inNewDatabaseTransaction(() -> {
				repository.lockForTransactionFinalization(transaction.transactionId());
				var future = executor.submit(() -> finalizationService.finalizeTransaction(transaction.transactionId()));
				awaitBlockedWorker();
				repository.updateBalance(account, 1_100);
				repository.updateOutcome(transaction.transactionId(), TransactionStatus.FINALIZED, null, NOW);
				return future;
			});
			blocked.get(5, TimeUnit.SECONDS);
		}
		assertThat(repository.getBalance(account)).isEqualTo(1_100);
		assertThat(storedTransaction(transaction.transactionId()).status()).isEqualTo(TransactionStatus.FINALIZED);
	}

	@Test
	void independentAccountFinalizesWhileAnotherWorkerIsWaitingOnALock() throws Exception {
		String a = newAccount(1_000);
		String b = newAccount(500);
		Transaction first = pending(TransactionType.CREDIT, 100, null, a);
		Transaction second = pending(TransactionType.CREDIT, 100, null, b);
		repository.insertPending(first);
		repository.insertPending(second);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var blocked = boundary.inNewDatabaseTransaction(() -> {
				repository.lockForTransactionFinalization(first.transactionId());
				var waiting = executor.submit(() -> finalizationService.finalizeTransaction(first.transactionId()));
				awaitBlockedWorker();
				var independent = executor.submit(() -> finalizationService.finalizeTransaction(second.transactionId()));
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
		var executing = pending(TransactionType.TRANSFER, 100, a, b);
		var incoming = pending(TransactionType.TRANSFER, 100, b, a);
		repository.insertPending(executing);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			boundary.inNewDatabaseTransaction(() -> {
				repository.lockForTransactionFinalization(executing.transactionId());
				var admission = executor.submit(() -> repository.insertPending(incoming));
				assertThatCode(() -> admission.get(3, TimeUnit.SECONDS)).doesNotThrowAnyException();
				return null;
			});
		}
		assertThat(transactionCount(incoming.transactionId())).isEqualTo(1);
	}

	@Test
	void aTransientFailureRollsBackTheFirstAttemptBeforeRetrying() {
		String a = newAccount(1_000);
		String b = newAccount(500);
		var transfer = pending(TransactionType.TRANSFER, 300, a, b);
		repository.insertPending(transfer);
		var failures = new AtomicInteger();
		var failing = mock(BalanceRepository.class, delegatesTo(repository));
		doAnswer(call -> {
			if (failures.getAndIncrement() == 0) throw new DataAccessResourceFailureException("transient error after balance writes");
			repository.updateOutcome(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
			return null;
		}).when(failing).updateOutcome(anyString(), any(), any(), any());
		new TransactionFinalizationServiceImpl(failing, boundary, Clock.systemUTC(), 1, Duration.ZERO).finalizeTransaction(transfer.transactionId());
		assertThat(failures.get()).isEqualTo(2);
		assertThat(repository.getBalance(a)).isEqualTo(700);
		assertThat(repository.getBalance(b)).isEqualTo(800);
		assertThat(storedTransaction(transfer.transactionId()).status()).isEqualTo(TransactionStatus.FINALIZED);
	}

	@Test
	void failureFinalizationWaitingOnAWinningWorkerCannotOverwriteItsOutcome() throws Exception {
		String account = newAccount(1_000);
		var transaction = pending(TransactionType.CREDIT, 100, null, account);
		repository.insertPending(transaction);
		var failing = mock(BalanceRepository.class, delegatesTo(repository));
		doThrow(new DataAccessResourceFailureException("first attempt failed"))
				.doAnswer(call -> repository.lockForTransactionFinalization(transaction.transactionId()))
				.when(failing).lockForTransactionFinalization(transaction.transactionId());
		var service = new TransactionFinalizationServiceImpl(failing, boundary, Clock.systemUTC(), 0, Duration.ZERO);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var finalizer = boundary.inNewDatabaseTransaction(() -> {
				repository.lockForTransactionFinalization(transaction.transactionId());
				var future = executor.submit(() -> service.finalizeTransaction(transaction.transactionId()));
				awaitBlockedWorker();
				repository.updateBalance(account, 1_100);
				repository.updateOutcome(transaction.transactionId(), TransactionStatus.FINALIZED, null, NOW);
				return future;
			});
			finalizer.get(5, TimeUnit.SECONDS);
		}
		assertThat(repository.getBalance(account)).isEqualTo(1_100);
		assertThat(storedTransaction(transaction.transactionId()).status()).isEqualTo(TransactionStatus.FINALIZED);
	}

	@Test
	void scalarLockReadsBypassAnAlreadyCachedAccountEntity() {
		String account = newAccount(1_000);
		Transaction credit = pending(TransactionType.CREDIT, 100, null, account);
		Transaction debit = pending(TransactionType.DEBIT, 50, account, null);
		repository.insertPending(credit);
		repository.insertPending(debit);
		boundary.inNewDatabaseTransaction(() -> {
			assertThat(entityManager.find(AccountJpaEntity.class, account).getBalance()).isEqualTo(1_000);
			try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
				assertThatCode(() -> executor.submit(() -> finalizationService.finalizeTransaction(credit.transactionId())).get(5, TimeUnit.SECONDS))
						.doesNotThrowAnyException();
			}
			var snapshot = repository.lockForTransactionFinalization(debit.transactionId());
			assertThat(snapshot.lockedAccounts().get(account).balance()).isEqualTo(1_100);
			return null;
		});
	}

	@Test
	void anErrorBetweenTransferWritesRollsBackTheBalanceAndLeavesPendingWork() {
		String a = newAccount(1_000);
		String b = newAccount(500);
		var transfer = pending(TransactionType.TRANSFER, 300, a, b);
		repository.insertPending(transfer);
		assertThatThrownBy(() -> boundary.inNewDatabaseTransaction(() -> {
			repository.lockForTransactionFinalization(transfer.transactionId());
			repository.updateBalance(a, 700);
			throw new IllegalStateException("interrupted transfer");
		})).hasMessage("interrupted transfer");
		assertThat(repository.getBalance(a)).isEqualTo(1_000);
		assertThat(repository.getBalance(b)).isEqualTo(500);
		assertThat(storedTransaction(transfer.transactionId()).status()).isEqualTo(TransactionStatus.PENDING);
		finalizationService.finalizeTransaction(transfer.transactionId());
		assertThat(repository.getBalance(a)).isEqualTo(700);
		assertThat(repository.getBalance(b)).isEqualTo(800);
	}

	@Test
	void databaseFailureRecordingOutcomeRollsBackBothSidesOfATransfer() {
		String a = newAccount(1_000);
		String b = newAccount(500);
		var transfer = pending(TransactionType.TRANSFER, 300, a, b);
		repository.insertPending(transfer);
		// A real database error after both balance writes tests atomicity through the JPA transaction manager.
		String constraint = "test_" + newId().replace("-", "");
		boundary.inNewDatabaseTransaction(() -> entityManager.createNativeQuery("ALTER TABLE transactions ADD CONSTRAINT "
				+ constraint + " CHECK (transaction_id <> '" + transfer.transactionId() + "' OR status <> 'FINALIZED')").executeUpdate());
		try {
			new TransactionFinalizationServiceImpl(repository, boundary, Clock.systemUTC(), 0, Duration.ZERO).finalizeTransaction(transfer.transactionId());
			assertThat(repository.getBalance(a)).isEqualTo(1_000);
			assertThat(repository.getBalance(b)).isEqualTo(500);
			assertThat(storedTransaction(transfer.transactionId()).status()).isEqualTo(TransactionStatus.FAILED);
			assertThat(storedTransaction(transfer.transactionId()).failureReason()).isEqualTo("FINALIZATION_ERROR");
		}
		finally {
			boundary.inNewDatabaseTransaction(() -> entityManager.createNativeQuery("ALTER TABLE transactions DROP CONSTRAINT " + constraint).executeUpdate());
		}
	}

	@ParameterizedTest
	@EnumSource(value = TransactionType.class, names = {"CREDIT", "TRANSFER"})
	void overflowingBalancesFailWithoutChangingEitherAccount(TransactionType type) {
		String a = newAccount(1_000);
		String b = newAccount(Long.MAX_VALUE);
		var transaction = pending(type, 1, a, b);
		repository.insertPending(transaction);
		finalizationService.finalizeTransaction(transaction.transactionId());
		assertThat(repository.getBalance(a)).isEqualTo(1_000);
		assertThat(repository.getBalance(b)).isEqualTo(Long.MAX_VALUE);
		assertThat(storedTransaction(transaction.transactionId()).status()).isEqualTo(TransactionStatus.FAILED);
		assertThat(storedTransaction(transaction.transactionId()).failureReason()).isEqualTo("FINALIZATION_ERROR");
	}

	@Test
	void recoveryQuerySelectsOnlyOldPendingRowsInStableOrder() {
		String account = newAccount(1_000);
		var first = pending(TransactionType.CREDIT, 1, null, account);
		var second = Transaction.pending(newId(), TransactionType.CREDIT, 1, null, account, NOW.plusSeconds(1));
		var finalized = pending(TransactionType.CREDIT, 1, null, account);
		var failed = pending(TransactionType.DEBIT, 10_000, account, null);
		var recent = Transaction.pending(newId(), TransactionType.CREDIT, 1, null, account, Instant.now());
		List.of(first, second, finalized, failed, recent).forEach(repository::insertPending);
		finalizationService.finalizeTransaction(finalized.transactionId());
		finalizationService.finalizeTransaction(failed.transactionId());
		assertThat(repository.findStalePendingTransactionIds(Duration.ofHours(1)))
				.containsSubsequence(first.transactionId(), second.transactionId())
				.doesNotContain(finalized.transactionId(), failed.transactionId(), recent.transactionId());
	}

	@Test
	void persistenceRequiresDatabaseTransactionsAndRejectsMissingRecords() {
		assertThatThrownBy(() -> repository.lockForTransactionFinalization(newId())).isInstanceOf(IllegalTransactionStateException.class);
		assertThatThrownBy(() -> repository.updateBalance(newId(), 1)).isInstanceOf(IllegalTransactionStateException.class);
		assertThatThrownBy(() -> repository.getBalance(newId())).isInstanceOf(AccountNotFoundException.class);
		assertThatThrownBy(() -> boundary.inNewDatabaseTransaction(() -> repository.lockForTransactionFinalization(newId())))
				.isInstanceOf(TransactionNotFoundException.class);
		assertThatThrownBy(() -> boundary.inNewDatabaseTransaction(() -> { repository.updateBalance(newId(), 1); return null; }))
				.isInstanceOf(AccountNotFoundException.class);
		assertThatThrownBy(() -> boundary.inNewDatabaseTransaction(() -> { repository.updateOutcome(newId(), TransactionStatus.FINALIZED, null, NOW); return null; }))
				.isInstanceOf(TransactionNotFoundException.class);
	}

	@Test
	void terminalOutcomesCannotBeOverwritten() {
		var transaction = pending(TransactionType.CREDIT, 100, null, newAccount(1_000));
		repository.insertPending(transaction);
		finalizationService.finalizeTransaction(transaction.transactionId());
		assertThatThrownBy(() -> boundary.inNewDatabaseTransaction(() -> {
			repository.updateOutcome(transaction.transactionId(), TransactionStatus.FAILED, "FINALIZATION_ERROR", NOW);
			return null;
		})).isInstanceOf(TransactionAlreadyFinalizedException.class);
		assertThat(storedTransaction(transaction.transactionId()).status()).isEqualTo(TransactionStatus.FINALIZED);
	}

	@Test
	void databaseConstraintsRejectUnknownAccounts() {
		var unknown = pending(TransactionType.CREDIT, 1, null, newId());
		assertThatThrownBy(() -> repository.insertPending(unknown)).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(transactionCount(unknown.transactionId())).isZero();
	}

	private void awaitBlockedWorker() {
		await().atMost(Duration.ofSeconds(5)).until(() -> boundary.inNewDatabaseTransaction(() -> ((Number) entityManager.createNativeQuery("""
				SELECT COUNT(*) FROM pg_stat_activity
				WHERE datname = current_database() AND wait_event_type = 'Lock'
				AND query LIKE '%SELECT balance FROM accounts%'
				""").getSingleResult()).longValue()) > 0);
	}

	private Transaction pending(TransactionType type, long amount, String source, String destination) {
		return Transaction.pending(newId(), type, amount, type == TransactionType.CREDIT ? null : source,
				type == TransactionType.DEBIT ? null : destination, NOW);
	}

	private void assertBalances(TransactionType type, String source, String destination) {
		assertThat(repository.getBalance(source)).isEqualTo(type == TransactionType.CREDIT ? 1_000 : 900);
		assertThat(repository.getBalance(destination)).isEqualTo(type == TransactionType.DEBIT ? 500 : 600);
	}

	private void submit(TransactionInitializer initializer, TransactionType type, String source, String destination, long amount, String id) {
		switch (type) {
			case CREDIT -> initializer.credit(destination, amount, id);
			case DEBIT -> initializer.debit(source, amount, id);
			case TRANSFER -> initializer.transfer(source, destination, amount, id);
		}
	}
}
