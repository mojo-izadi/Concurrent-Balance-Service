package com.example.concurrentbalanceservice.repository;

import com.example.concurrentbalanceservice.domain.TransactionStatus;
import com.example.concurrentbalanceservice.domain.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface BalanceRepository {
	boolean accountExists(String accountId);

	long getBalance(String accountId);

	/** Inserts new work and commits it before returning, even inside a caller's database transaction. */
	void insertPending(Transaction transaction);

	/** Requires a database transaction; holds ordered account locks and checks the fresh pending status. */
	TransactionFinalizationSnapshot lockForTransactionFinalization(String transactionId);

	/** Updates a locked account balance within the active database transaction. */
	void updateBalance(String accountId, long balance);

	/** Updates the transaction outcome within the active database transaction. */
	void updateOutcome(String transactionId, TransactionStatus status, String failureReason, Instant updatedAt);

	List<String> findStalePendingTransactionIds(Duration minimumAge);
}
