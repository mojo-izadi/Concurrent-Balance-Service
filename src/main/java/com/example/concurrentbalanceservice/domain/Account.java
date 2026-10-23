package com.example.concurrentbalanceservice.domain;

public record Account(String accountId, long balance) {
	public Account {
		if (accountId == null || accountId.isBlank()) {
			throw new IllegalArgumentException("Account ID is required");
		}
		if (accountId.length() > 128) {
			throw new IllegalArgumentException("Account ID cannot exceed 128 characters");
		}
		if (balance < 0) {
			throw new IllegalArgumentException("Account balance cannot be negative");
		}
	}
}
