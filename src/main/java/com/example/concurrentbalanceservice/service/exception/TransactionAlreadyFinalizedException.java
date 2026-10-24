package com.example.concurrentbalanceservice.service.exception;

public class TransactionAlreadyFinalizedException extends RuntimeException {
	public TransactionAlreadyFinalizedException(String transactionId) {
		super("Transaction is already finalized: " + transactionId);
	}
}
