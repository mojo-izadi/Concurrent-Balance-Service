package com.example.concurrentbalanceservice.infrastructure.execution;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.concurrentbalanceservice.application.service.OperationExecutionService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class VirtualThreadJobExecutorTest {
	@Test
	void returnsWhileWorkRunsOnAVirtualThread() throws Exception {
		var service = mock(OperationExecutionService.class);
		var started = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var virtual = new AtomicBoolean();
		doAnswer(call -> {
			virtual.set(Thread.currentThread().isVirtual());
			started.countDown();
			assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			return null;
		}).when(service).execute("tx");
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			try {
				new VirtualThreadJobExecutor(executor, service, 10).execute("tx");
				assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
				assertThat(virtual.get()).isTrue();
			}
			finally {
				release.countDown();
			}
		}
		verify(service).execute("tx");
	}

	@Test
	void workerFailureDoesNotPreventSubsequentWork() {
		var service = mock(OperationExecutionService.class);
		doThrow(new IllegalStateException("unexpected")).when(service).execute("failed");
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var jobs = new VirtualThreadJobExecutor(executor, service, 10);
			jobs.execute("failed");
			jobs.execute("next");
		}
		verify(service).execute("failed");
		verify(service).execute("next");
	}

	@Test
	void dispatchRejectionIsVisibleToTheAdmissionAndRecoveryUseCases() {
		var service = mock(OperationExecutionService.class);
		var executor = Executors.newVirtualThreadPerTaskExecutor();
		executor.close();
		assertThatThrownBy(() -> new VirtualThreadJobExecutor(executor, service, 10).execute("tx"))
				.isInstanceOf(RejectedExecutionException.class);
		verifyNoInteractions(service);
	}
}
