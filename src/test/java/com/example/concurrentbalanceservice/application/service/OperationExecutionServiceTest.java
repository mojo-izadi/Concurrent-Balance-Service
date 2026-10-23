package com.example.concurrentbalanceservice.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.concurrentbalanceservice.application.exception.NonRetryableExecutionException;
import com.example.concurrentbalanceservice.application.exception.OperationAlreadyFinalizedException;
import com.example.concurrentbalanceservice.application.exception.OperationNotFoundException;
import com.example.concurrentbalanceservice.application.port.BalanceRepository;
import com.example.concurrentbalanceservice.application.port.ExecutionSnapshot;
import com.example.concurrentbalanceservice.application.port.TransactionBoundary;
import com.example.concurrentbalanceservice.domain.Account;
import com.example.concurrentbalanceservice.domain.OperationStatus;
import com.example.concurrentbalanceservice.domain.OperationType;
import com.example.concurrentbalanceservice.domain.Transaction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class OperationExecutionServiceTest {
	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
	private final BalanceRepository repository = mock(BalanceRepository.class);
	private final DirectTransactions boundary = new DirectTransactions();
	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	private final OperationExecutionService service = new OperationExecutionService(repository, boundary, clock, 2, Duration.ZERO);

	@Test
	void balancesAndOutcomeAreWrittenInsideTheSameAttempt() {
		when(repository.lockForTransactionExecution("tx")).thenReturn(transfer(100, 500));
		doAnswer(call -> { assertThat(boundary.active).isTrue(); return null; }).when(repository).updateBalance(anyString(), anyLong());
		doAnswer(call -> { assertThat(boundary.active).isTrue(); return null; }).when(repository).updateOutcome(anyString(), any(), any(), any());
		service.execute("tx");
		var order = inOrder(repository);
		order.verify(repository).lockForTransactionExecution("tx");
		verify(repository).updateBalance("A", 400);
		verify(repository).updateBalance("B", 600);
		order.verify(repository).updateOutcome("tx", OperationStatus.FINISHED, null, NOW);
		assertThat(boundary.calls).isEqualTo(1);
	}

	@Test
	void insufficientFundsCommitFailureWithoutUpdatingBalances() {
		when(repository.lockForTransactionExecution("tx")).thenReturn(transfer(600, 500));
		service.execute("tx");
		verify(repository).updateOutcome("tx", OperationStatus.FAILED, "INSUFFICIENT_BALANCE", NOW);
		verify(repository, never()).updateBalance(anyString(), anyLong());
		assertThat(boundary.calls).isEqualTo(1);
	}

	@Test
	void technicalFailuresRetryInFreshTransactions() {
		when(repository.lockForTransactionExecution("tx")).thenThrow(new IllegalStateException("transient"))
				.thenReturn(transfer(100, 500));
		service.execute("tx");
		assertThat(boundary.calls).isEqualTo(2);
		verify(repository).updateOutcome("tx", OperationStatus.FINISHED, null, NOW);
	}

	@Test
	void aConfiguredRetryDelayAllowsTheNextAttemptToComplete() {
		when(repository.lockForTransactionExecution("tx")).thenThrow(new IllegalStateException("transient"))
				.thenReturn(transfer(100, 500));
		new OperationExecutionService(repository, boundary, clock, 1, Duration.ofMillis(1)).execute("tx");
		assertThat(boundary.calls).isEqualTo(2);
		verify(repository).updateOutcome("tx", OperationStatus.FINISHED, null, NOW);
	}

	@Test
	void failedCommitAlsoRetriesTheEntireAttempt() {
		var commits = new TransactionBoundary() {
			int calls;
			public <T> T inNewTransaction(Supplier<T> work) {
				T result = work.get();
				if (++calls == 1) throw new IllegalStateException("commit failed");
				return result;
			}
		};
		when(repository.lockForTransactionExecution("tx")).thenReturn(transfer(100, 500));
		new OperationExecutionService(repository, commits, clock, 1, Duration.ZERO).execute("tx");
		verify(repository, times(2)).lockForTransactionExecution("tx");
	}

	@Test
	void exhaustedAttemptsUseASeparateGuardedFinalization() {
		when(repository.lockForTransactionExecution("tx")).thenReturn(transfer(100, 500));
		doThrow(new IllegalStateException("write failed")).when(repository).updateBalance(anyString(), anyLong());
		service.execute("tx");
		assertThat(boundary.calls).isEqualTo(4); // initial + two retries + finalization
		verify(repository, times(4)).lockForTransactionExecution("tx");
		verify(repository).updateOutcome("tx", OperationStatus.FAILED, "EXECUTION_ERROR", NOW);
	}

	@Test
	void zeroRetriesStillMakesOneAttemptAndOneFinalization() {
		when(repository.lockForTransactionExecution("tx")).thenThrow(new IllegalStateException()).thenReturn(transfer(1, 500));
		new OperationExecutionService(repository, boundary, clock, 0, Duration.ZERO).execute("tx");
		assertThat(boundary.calls).isEqualTo(2);
		verify(repository).updateOutcome("tx", OperationStatus.FAILED, "EXECUTION_ERROR", NOW);
	}

	@Test
	void nonRetryableErrorsSkipTheRetryBudget() {
		when(repository.lockForTransactionExecution("tx"))
				.thenThrow(new NonRetryableExecutionException("corrupt", new IllegalArgumentException()))
				.thenReturn(transfer(1, 500));
		service.execute("tx");
		assertThat(boundary.calls).isEqualTo(2);
		verify(repository).updateOutcome("tx", OperationStatus.FAILED, "EXECUTION_ERROR", NOW);
	}

	@Test
	void overflowIsNonRetryableAndDoesNotWriteBalances() {
		var transaction = Transaction.pending("tx", OperationType.CREDIT, 1, null, "A", NOW);
		when(repository.lockForTransactionExecution("tx")).thenReturn(new ExecutionSnapshot(transaction, Map.of("A", new Account("A", Long.MAX_VALUE))));
		service.execute("tx");
		assertThat(boundary.calls).isEqualTo(2);
		verify(repository, never()).updateBalance(anyString(), anyLong());
		verify(repository).updateOutcome("tx", OperationStatus.FAILED, "EXECUTION_ERROR", NOW);
	}

	@Test
	void missingOrFinalizedOperationsAreNoOps() {
		for (var exception : new RuntimeException[]{new OperationNotFoundException("tx"), new OperationAlreadyFinalizedException("tx")}) {
			doThrow(exception).when(repository).lockForTransactionExecution("tx");
			service.execute("tx");
		}
		assertThat(boundary.calls).isEqualTo(2);
		verify(repository, never()).updateBalance(anyString(), anyLong());
		verify(repository, never()).updateOutcome(anyString(), any(), any(), any());
	}

	@Test
	void finalizationNeverOverwritesACompetingWorkersCompletion() {
		when(repository.lockForTransactionExecution("tx")).thenThrow(new IllegalStateException())
				.thenThrow(new OperationAlreadyFinalizedException("tx"));
		new OperationExecutionService(repository, boundary, clock, 0, Duration.ZERO).execute("tx");
		verify(repository, never()).updateOutcome(anyString(), any(), any(), any());
	}

	@Test
	void unavailableFinalizationLeavesTheOperationForRecovery() {
		when(repository.lockForTransactionExecution("tx")).thenThrow(new IllegalStateException("offline"));
		assertThatCode(() -> service.execute("tx")).doesNotThrowAnyException();
		assertThat(boundary.calls).isEqualTo(4);
		verify(repository, never()).updateOutcome(anyString(), any(), any(), any());
	}

	@Test
	void interruptedRetryPreservesInterruptAndLeavesPending() {
		when(repository.lockForTransactionExecution("tx")).thenThrow(new IllegalStateException());
		Thread.currentThread().interrupt();
		try {
			new OperationExecutionService(repository, boundary, clock, 2, Duration.ofMillis(1)).execute("tx");
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
		assertThatIllegalArgumentException().isThrownBy(() -> new OperationExecutionService(repository, boundary, clock, -1, Duration.ZERO));
		assertThatIllegalArgumentException().isThrownBy(() -> new OperationExecutionService(repository, boundary, clock, 0, Duration.ofMillis(-1)));
	}

	private ExecutionSnapshot transfer(long amount, long sourceBalance) {
		return new ExecutionSnapshot(Transaction.pending("tx", OperationType.TRANSFER, amount, "A", "B", NOW),
				Map.of("A", new Account("A", sourceBalance), "B", new Account("B", 500)));
	}

	private static class DirectTransactions implements TransactionBoundary {
		int calls;
		boolean active;
		public <T> T inNewTransaction(Supplier<T> work) {
			calls++;
			active = true;
			try { return work.get(); }
			finally { active = false; }
		}
	}
}
