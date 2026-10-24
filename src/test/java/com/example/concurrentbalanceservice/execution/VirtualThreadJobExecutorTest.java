package com.example.concurrentbalanceservice.execution;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.concurrentbalanceservice.service.TransactionFinalizationService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class VirtualThreadJobExecutorTest {
	@Test
	void returnsWhileFinalizationRunsOnAVirtualThread() throws Exception {
		var service = mock(TransactionFinalizationService.class);
		var started = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var virtual = new AtomicBoolean();
		doAnswer(call -> {
			virtual.set(Thread.currentThread().isVirtual());
			started.countDown();
			assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			return null;
		}).when(service).finalizeTransaction("tx");
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			try {
				new VirtualThreadJobExecutor(executor, service, 10).submitFinalization("tx");
				assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
				assertThat(virtual.get()).isTrue();
			}
			finally {
				release.countDown();
			}
		}
		verify(service).finalizeTransaction("tx");
	}

	@Test
	void workerFailureDoesNotPreventSubsequentFinalization() {
		var service = mock(TransactionFinalizationService.class);
		doThrow(new IllegalStateException("unexpected")).when(service).finalizeTransaction("failed");
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var jobs = new VirtualThreadJobExecutor(executor, service, 10);
			jobs.submitFinalization("failed");
			jobs.submitFinalization("next");
		}
		verify(service).finalizeTransaction("failed");
		verify(service).finalizeTransaction("next");
	}

	@Test
	void dispatchRejectionIsVisibleToAdmissionAndRecovery() {
		var service = mock(TransactionFinalizationService.class);
		var executor = Executors.newVirtualThreadPerTaskExecutor();
		executor.close();
		assertThatThrownBy(() -> new VirtualThreadJobExecutor(executor, service, 10).submitFinalization("tx"))
				.isInstanceOf(RejectedExecutionException.class);
		verifyNoInteractions(service);
	}
}
