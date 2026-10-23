package com.example.concurrentbalanceservice.application.exception;

public class OperationAlreadyFinalizedException extends RuntimeException {
	public OperationAlreadyFinalizedException(String transactionId) {
		super("Operation is already finalized: " + transactionId);
	}
}
