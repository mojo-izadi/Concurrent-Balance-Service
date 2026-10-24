package com.example.concurrentbalanceservice.service.exception;

public class DuplicateTransactionIdException extends RuntimeException {
	public DuplicateTransactionIdException(String transactionId) {
		super("Transaction ID has already been used: " + transactionId);
	}
}
