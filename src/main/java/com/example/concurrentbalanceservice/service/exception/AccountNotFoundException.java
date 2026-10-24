package com.example.concurrentbalanceservice.service.exception;

public class AccountNotFoundException extends RuntimeException {
	public AccountNotFoundException(String accountId) {
		super("Account not found: " + accountId);
	}
}
