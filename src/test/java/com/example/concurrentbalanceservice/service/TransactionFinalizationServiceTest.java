package com.example.concurrentbalanceservice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.concurrentbalanceservice.domain.Account;
import com.example.concurrentbalanceservice.domain.TransactionStatus;
import com.example.concurrentbalanceservice.domain.TransactionType;
import com.example.concurrentbalanceservice.domain.Transaction;
import com.example.concurrentbalanceservice.repository.BalanceRepository;
import com.example.concurrentbalanceservice.repository.TransactionFinalizationSnapshot;
import com.example.concurrentbalanceservice.service.exception.NonRetryableFinalizationException;
import com.example.concurrentbalanceservice.service.exception.TransactionAlreadyFinalizedException;
import com.example.concurrentbalanceservice.service.exception.TransactionNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TransactionFinalizationServiceTest {
	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
	private final BalanceRepository repository = mock(BalanceRepository.class);
	private final DirectDatabaseTransactions boundary = new DirectDatabaseTransactions();
	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	private final TransactionFinalizationService service = new TransactionFinalizationServiceImpl(repository, boundary, clock, 2, Duration.ZERO);

	@Test
	void balancesAndOutcomeAreWrittenInsideTheSameAttempt() {
		when(repository.lockForTransactionFinalization("tx")).thenReturn(transfer(100, 500));
		doAnswer(call -> { assertThat(boundary.active).isTrue(); return null; }).when(repository).updateBalance(anyString(), anyLong());
		doAnswer(call -> { assertThat(boundary.active).isTrue(); return null; }).when(repository).updateOutcome(anyString(), any(), any(), any());
		service.finalizeTransaction("tx");
		var order = inOrder(repository);
		order.verify(repository).lockForTransactionFinalization("tx");
		verify(repository).updateBalance("A", 400);
		verify(repository).updateBalance("B", 600);
		order.verify(repository).updateOutcome("tx", TransactionStatus.FINALIZED, null, NOW);
		assertThat(boundary.calls).isEqualTo(1);
	}

	@Test
	void insufficientFundsCommitFailureWithoutUpdatingBalances() {
		when(repository.lockForTransactionFinalization("tx")).thenReturn(transfer(600, 500));
		service.finalizeTransaction("tx");
		verify(repository).updateOutcome("tx", TransactionStatus.FAILED, "INSUFFICIENT_BALANCE", NOW);
		verify(repository, never()).updateBalance(anyString(), anyLong());
		assertThat(boundary.calls).isEqualTo(1);
	}

	@Test
	void technicalFailuresRetryInFreshDatabaseTransactions() {
		when(repository.lockForTransactionFinalization("tx")).thenThrow(new IllegalStateException("transient"))
				.thenReturn(transfer(100, 500));
		service.finalizeTransaction("tx");
		assertThat(boundary.calls).isEqualTo(2);
		verify(repository).updateOutcome("tx", TransactionStatus.FINALIZED, null, NOW);
	}

	@Test
	void aConfiguredRetryDelayAllowsTheNextAttemptToComplete() {
		when(repository.lockForTransactionFinalization("tx")).thenThrow(new IllegalStateException("transient"))
				.thenReturn(transfer(100, 500));
		new TransactionFinalizationServiceImpl(repository, boundary, clock, 1, Duration.ofMillis(1)).finalizeTransaction("tx");
		assertThat(boundary.calls).isEqualTo(2);
		verify(repository).updateOutcome("tx", TransactionStatus.FINALIZED, null, NOW);
	}

	@Test
	void failedCommitAlsoRetriesTheEntireAttempt() {
		var commits = new DatabaseTransactionBoundary() {
			int calls;
			public <T> T inNewDatabaseTransaction(Supplier<T> work) {
				T result = work.get();
				if (++calls == 1) throw new IllegalStateException("commit failed");
				return result;
			}
		};
		when(repository.lockForTransactionFinalization("tx")).thenReturn(transfer(100, 500));
		new TransactionFinalizationServiceImpl(repository, commits, clock, 1, Duration.ZERO).finalizeTransaction("tx");
		verify(repository, times(2)).lockForTransactionFinalization("tx");
	}

	@Test
	void exhaustedAttemptsUseASeparateGuardedFinalization() {
		when(repository.lockForTransactionFinalization("tx")).thenReturn(transfer(100, 500));
		doThrow(new IllegalStateException("write failed")).when(repository).updateBalance(anyString(), anyLong());
		service.finalizeTransaction("tx");
		assertThat(boundary.calls).isEqualTo(4); // initial + two retries + finalization
		verify(repository, times(4)).lockForTransactionFinalization("tx");
		verify(repository).updateOutcome("tx", TransactionStatus.FAILED, "FINALIZATION_ERROR", NOW);
	}

	@Test
	void zeroRetriesStillMakesOneAttemptAndOneFinalization() {
		when(repository.lockForTransactionFinalization("tx")).thenThrow(new IllegalStateException()).thenReturn(transfer(1, 500));
		new TransactionFinalizationServiceImpl(repository, boundary, clock, 0, Duration.ZERO).finalizeTransaction("tx");
		assertThat(boundary.calls).isEqualTo(2);
		verify(repository).updateOutcome("tx", TransactionStatus.FAILED, "FINALIZATION_ERROR", NOW);
	}

	@Test
	void nonRetryableErrorsSkipTheRetryBudget() {
		when(repository.lockForTransactionFinalization("tx"))
				.thenThrow(new NonRetryableFinalizationException("corrupt", new IllegalArgumentException()))
				.thenReturn(transfer(1, 500));
		service.finalizeTransaction("tx");
		assertThat(boundary.calls).isEqualTo(2);
		verify(repository).updateOutcome("tx", TransactionStatus.FAILED, "FINALIZATION_ERROR", NOW);
	}

	@Test
	void overflowIsNonRetryableAndDoesNotWriteBalances() {
		var transaction = Transaction.pending("tx", TransactionType.CREDIT, 1, null, "A", NOW);
		when(repository.lockForTransactionFinalization("tx")).thenReturn(new TransactionFinalizationSnapshot(transaction, Map.of("A", new Account("A", Long.MAX_VALUE))));
		service.finalizeTransaction("tx");
		assertThat(boundary.calls).isEqualTo(2);
		verify(repository, never()).updateBalance(anyString(), anyLong());
		verify(repository).updateOutcome("tx", TransactionStatus.FAILED, "FINALIZATION_ERROR", NOW);
	}

	@Test
	void missingOrFinalizedTransactionsAreNoOps() {
		for (var exception : new RuntimeException[]{new TransactionNotFoundException("tx"), new TransactionAlreadyFinalizedException("tx")}) {
			doThrow(exception).when(repository).lockForTransactionFinalization("tx");
			service.finalizeTransaction("tx");
		}
		assertThat(boundary.calls).isEqualTo(2);
		verify(repository, never()).updateBalance(anyString(), anyLong());
		verify(repository, never()).updateOutcome(anyString(), any(), any(), any());
	}

	@Test
	void finalizationNeverOverwritesACompetingWorkersCompletion() {
		when(repository.lockForTransactionFinalization("tx")).thenThrow(new IllegalStateException())
				.thenThrow(new TransactionAlreadyFinalizedException("tx"));
		new TransactionFinalizationServiceImpl(repository, boundary, clock, 0, Duration.ZERO).finalizeTransaction("tx");
		verify(repository, never()).updateOutcome(anyString(), any(), any(), any());
	}

	@Test
	void unavailableFinalizationLeavesTheTransactionForRecovery() {
		when(repository.lockForTransactionFinalization("tx")).thenThrow(new IllegalStateException("offline"));
		assertThatCode(() -> service.finalizeTransaction("tx")).doesNotThrowAnyException();
		assertThat(boundary.calls).isEqualTo(4);
		verify(repository, never()).updateOutcome(anyString(), any(), any(), any());
	}

	@Test
	void interruptedRetryPreservesInterruptAndLeavesPending() {
		when(repository.lockForTransactionFinalization("tx")).thenThrow(new IllegalStateException());
		Thread.currentThread().interrupt();
		try {
			new TransactionFinalizationServiceImpl(repository, boundary, clock, 2, Duration.ofMillis(1)).finalizeTransaction("tx");
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
			assertThat(boundary.calls).isEqualTo(1);
			verify(repository, never()).updateOutcome(anyString(), any(), any(), any());
		}
		finally {
			Thread.interrupted();
		}
	}

	@Test
	void rejectsNegativeRetrySettings() {
		assertThatIllegalArgumentException().isThrownBy(() -> new TransactionFinalizationServiceImpl(repository, boundary, clock, -1, Duration.ZERO));
		assertThatIllegalArgumentException().isThrownBy(() -> new TransactionFinalizationServiceImpl(repository, boundary, clock, 0, Duration.ofMillis(-1)));
	}

	private TransactionFinalizationSnapshot transfer(long amount, long sourceBalance) {
		return new TransactionFinalizationSnapshot(Transaction.pending("tx", TransactionType.TRANSFER, amount, "A", "B", NOW),
				Map.of("A", new Account("A", sourceBalance), "B", new Account("B", 500)));
	}

	private static class DirectDatabaseTransactions implements DatabaseTransactionBoundary {
		int calls;
		boolean active;
		public <T> T inNewDatabaseTransaction(Supplier<T> work) {
			calls++;
			active = true;
			try { return work.get(); }
			finally { active = false; }
		}
	}
}
