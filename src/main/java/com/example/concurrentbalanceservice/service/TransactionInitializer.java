package com.example.concurrentbalanceservice.service;

public interface TransactionInitializer {
	void credit(String accountId, long amount, String transactionId);

	void debit(String accountId, long amount, String transactionId);

	void transfer(String sourceAccountId, String destinationAccountId, long amount, String transactionId);
}
