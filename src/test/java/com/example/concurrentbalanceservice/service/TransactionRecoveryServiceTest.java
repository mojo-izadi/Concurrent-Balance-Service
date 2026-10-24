package com.example.concurrentbalanceservice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.concurrentbalanceservice.execution.JobExecutor;
import com.example.concurrentbalanceservice.repository.BalanceRepository;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class TransactionRecoveryServiceTest {
	private final BalanceRepository repository = mock(BalanceRepository.class);
	private final JobExecutor executor = mock(JobExecutor.class);
	private final Duration minimumAge = Duration.ofSeconds(5);
	private final TransactionRecoveryService service = new TransactionRecoveryServiceImpl(repository, executor, minimumAge);

	@Test
	void schedulesExistingIdsWithoutRegisteringNewWork() {
		when(repository.findStalePendingTransactionIds(minimumAge)).thenReturn(List.of("old-1", "old-2"));
		service.recoverPendingTransactions();
		verify(executor).submitFinalization("old-1");
		verify(executor).submitFinalization("old-2");
		verify(repository).findStalePendingTransactionIds(minimumAge);
		verifyNoMoreInteractions(repository, executor);
	}

	@Test
	void oneRejectedDispatchDoesNotBlockOtherRecoveryCandidates() {
		when(repository.findStalePendingTransactionIds(minimumAge)).thenReturn(List.of("old-1", "old-2"));
		doThrow(new RejectedExecutionException()).when(executor).submitFinalization("old-1");
		service.recoverPendingTransactions();
		verify(executor).submitFinalization("old-2");
	}

	@Test
	void emptyScanDoesNotDispatch() {
		when(repository.findStalePendingTransactionIds(minimumAge)).thenReturn(List.of());
		service.recoverPendingTransactions();
		verifyNoInteractions(executor);
	}

	@Test
	void rejectsNegativeAge() {
		assertThatIllegalArgumentException().isThrownBy(() -> new TransactionRecoveryServiceImpl(repository, executor, Duration.ofSeconds(-1)));
	}
}
