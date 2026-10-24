package com.example.concurrentbalanceservice.execution;

public interface JobExecutor {
	/** Dispatches without waiting; rejected work remains pending for a later recovery scan. */
	void submitFinalization(String transactionId);
}
