package com.example.concurrentbalanceservice.application.exception;

public class DuplicateTransactionIdException extends RuntimeException {
	public DuplicateTransactionIdException(String transactionId) {
		super("Transaction ID has already been used: " + transactionId);
	}
}
