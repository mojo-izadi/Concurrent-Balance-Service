package com.example.concurrentbalanceservice.application.port;

public interface JobExecutor {
	/** Dispatches without waiting; rejected work remains pending for a later recovery scan. */
	void execute(String transactionId);
}
