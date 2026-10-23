package com.example.concurrentbalanceservice.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.concurrentbalanceservice.application.port.BalanceRepository;
import com.example.concurrentbalanceservice.application.port.JobExecutor;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class OperationRecoveryServiceTest {
	private final BalanceRepository repository = mock(BalanceRepository.class);
	private final JobExecutor executor = mock(JobExecutor.class);
	private final Duration minimumAge = Duration.ofSeconds(5);
	private final OperationRecoveryService service = new OperationRecoveryService(repository, executor, minimumAge);

	@Test
	void schedulesExistingIdsWithoutRegisteringNewWork() {
		when(repository.findStalePendingTransactionIds(minimumAge)).thenReturn(List.of("old-1", "old-2"));
		service.recoverPendingOperations();
		verify(executor).execute("old-1");
		verify(executor).execute("old-2");
		verify(repository).findStalePendingTransactionIds(minimumAge);
		verifyNoMoreInteractions(repository, executor);
	}

	@Test
	void oneRejectedDispatchDoesNotBlockOtherRecoveryCandidates() {
		when(repository.findStalePendingTransactionIds(minimumAge)).thenReturn(List.of("old-1", "old-2"));
		doThrow(new RejectedExecutionException()).when(executor).execute("old-1");
		service.recoverPendingOperations();
		verify(executor).execute("old-2");
	}

	@Test
	void emptyScanDoesNotDispatch() {
		when(repository.findStalePendingTransactionIds(minimumAge)).thenReturn(List.of());
		service.recoverPendingOperations();
		verifyNoInteractions(executor);
	}

	@Test
	void rejectsNegativeAge() {
		assertThatIllegalArgumentException().isThrownBy(() -> new OperationRecoveryService(repository, executor, Duration.ofSeconds(-1)));
	}
}
