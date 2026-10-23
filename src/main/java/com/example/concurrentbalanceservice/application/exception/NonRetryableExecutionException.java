package com.example.concurrentbalanceservice.application.exception;

public class NonRetryableExecutionException extends RuntimeException {
	public NonRetryableExecutionException(String message, Throwable cause) {
		super(message, cause);
	}
}
