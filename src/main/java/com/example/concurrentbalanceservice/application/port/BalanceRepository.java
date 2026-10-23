package com.example.concurrentbalanceservice.application.port;

import com.example.concurrentbalanceservice.domain.Transaction;
import com.example.concurrentbalanceservice.domain.OperationStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface BalanceRepository {
	boolean accountExists(String accountId);

	long getBalance(String accountId);

	/** Inserts new work and commits it before returning, even inside a caller's transaction. */
	void insertPending(Transaction transaction);

	/** Requires a transaction; holds ordered account locks and checks the fresh pending status. */
	ExecutionSnapshot lockForTransactionExecution(String transactionId);

	void updateBalance(String accountId, long balance);

	void updateOutcome(String transactionId, OperationStatus status, String failureReason, Instant updatedAt);

	List<String> findStalePendingTransactionIds(Duration minimumAge);
}
