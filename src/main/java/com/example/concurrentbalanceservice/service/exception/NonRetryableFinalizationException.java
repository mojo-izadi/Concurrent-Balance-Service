package com.example.concurrentbalanceservice.service.exception;

public class NonRetryableFinalizationException extends RuntimeException {
	public NonRetryableFinalizationException(String message, Throwable cause) {
		super(message, cause);
	}
}
