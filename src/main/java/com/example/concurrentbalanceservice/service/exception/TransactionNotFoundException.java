package com.example.concurrentbalanceservice.service.exception;

public class TransactionNotFoundException extends RuntimeException {
	public TransactionNotFoundException(String transactionId) {
		super("Transaction not found: " + transactionId);
	}
}
