package com.example.concurrentbalanceservice.application.exception;

public class OperationNotFoundException extends RuntimeException {
	public OperationNotFoundException(String transactionId) {
		super("Operation not found: " + transactionId);
	}
}
