package com.example.concurrentbalanceservice.application.exception;

public class AccountNotFoundException extends RuntimeException {
	public AccountNotFoundException(String accountId) {
		super("Account not found: " + accountId);
	}
}
