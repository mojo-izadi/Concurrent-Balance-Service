package com.example.concurrentbalanceservice.execution;

import com.example.concurrentbalanceservice.service.TransactionFinalizationService;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class VirtualThreadJobExecutor implements JobExecutor {
	private static final Logger LOGGER = Logger.getLogger(VirtualThreadJobExecutor.class.getName());
	private final ExecutorService executor;
	private final TransactionFinalizationService finalizationService;
	private final Semaphore finalizationSlots;
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

	public VirtualThreadJobExecutor(@Qualifier("balanceVirtualThreadExecutor") ExecutorService executor,
			TransactionFinalizationService finalizationService,
			@Value("${balance.finalization.max-concurrent-transactions:10}") int maxConcurrentTransactions) {
		if (maxConcurrentTransactions <= 0) {
			throw new IllegalArgumentException("Maximum concurrent transactions must be greater than zero");
		}
		this.executor = executor;
		this.finalizationService = finalizationService;
		this.finalizationSlots = new Semaphore(maxConcurrentTransactions);
	}

	@Override
	public void submitFinalization(String transactionId) {
		if (!inFlight.add(transactionId)) {
			return;
		}
		// Claim capacity before creating a virtual thread; pending rows are the durable backlog.
		if (!finalizationSlots.tryAcquire()) {
			inFlight.remove(transactionId);
			throw new RejectedExecutionException("Transaction finalization capacity is full");
		}
		try {
			executor.execute(() -> {
				try {
					finalizationService.finalizeTransaction(transactionId);
				}
				catch (RuntimeException exception) {
					LOGGER.log(Level.SEVERE, "Unexpected worker failure for transaction " + transactionId, exception);
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
		finalizationSlots.release();
	}
}
