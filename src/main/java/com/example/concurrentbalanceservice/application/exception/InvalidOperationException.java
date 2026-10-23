package com.example.concurrentbalanceservice.application.exception;

public class InvalidOperationException extends RuntimeException {
	public InvalidOperationException(String message) {
		super(message);
	}
}
