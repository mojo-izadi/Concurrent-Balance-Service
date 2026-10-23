package com.example.concurrentbalanceservice.infrastructure.execution;

import com.example.concurrentbalanceservice.application.port.JobExecutor;
import com.example.concurrentbalanceservice.application.service.OperationExecutionService;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VirtualThreadJobExecutor implements JobExecutor {
	private static final Logger LOGGER = Logger.getLogger(VirtualThreadJobExecutor.class.getName());
	private final ExecutorService executor;
	private final OperationExecutionService executionService;
	private final Semaphore executionSlots;
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

	public VirtualThreadJobExecutor(ExecutorService executor, OperationExecutionService executionService,
			int maxConcurrentOperations) {
		if (maxConcurrentOperations <= 0) {
			throw new IllegalArgumentException("Maximum concurrent operations must be greater than zero");
		}
		this.executor = executor;
		this.executionService = executionService;
		this.executionSlots = new Semaphore(maxConcurrentOperations);
	}

	@Override
	public void execute(String transactionId) {
		if (!inFlight.add(transactionId)) {
			return;
		}
		// Claim capacity before creating a virtual thread; pending rows are the durable backlog.
		if (!executionSlots.tryAcquire()) {
			inFlight.remove(transactionId);
			throw new RejectedExecutionException("Operation execution capacity is full");
		}
		try {
			executor.execute(() -> {
				try {
					executionService.execute(transactionId);
				}
				catch (RuntimeException exception) {
					LOGGER.log(Level.SEVERE, "Unexpected worker failure for operation " + transactionId, exception);
				}
				finally {
					releaseSlot(transactionId);
				}
			});
		}
		catch (RuntimeException | Error exception) {
			releaseSlot(transactionId);
			throw exception;
		}
	}

	private void releaseSlot(String transactionId) {
		inFlight.remove(transactionId);
		executionSlots.release();
	}
}
