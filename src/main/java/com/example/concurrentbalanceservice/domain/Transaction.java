package com.example.concurrentbalanceservice.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record Transaction(
		String transactionId,
		TransactionType type,
		long amount,
		TransactionStatus status,
		String sourceAccountId,
		String destinationAccountId,
		String failureReason,
		Instant updatedAt) {

	public Transaction {
		if (transactionId == null || transactionId.isBlank()) {
			throw new IllegalArgumentException("Transaction ID is required");
		}
		if (transactionId.length() > 128) {
			throw new IllegalArgumentException("Transaction ID cannot exceed 128 characters");
		}
		if (type == null || status == null || updatedAt == null) {
			throw new IllegalArgumentException("Transaction type, status, and update time are required");
		}
		if (amount <= 0) {
			throw new IllegalArgumentException("Amount must be greater than zero");
		}
		validateAccountReferences(type, sourceAccountId, destinationAccountId);
		if (status == TransactionStatus.FAILED && (failureReason == null || failureReason.isBlank())) {
			throw new IllegalArgumentException("A failed transaction must have a failure reason");
		}
		if (status != TransactionStatus.FAILED && failureReason != null) {
			throw new IllegalArgumentException("Only failed transactions have a failure reason");
		}
	}

	public static Transaction pending(String transactionId, TransactionType type, long amount,
			String sourceAccountId, String destinationAccountId, Instant now) {
		return new Transaction(transactionId, type, amount, TransactionStatus.PENDING,
				sourceAccountId, destinationAccountId, null, now);
	}

	public Transaction withStatus(TransactionStatus newStatus, String newFailureReason, Instant now) {
		return new Transaction(transactionId, type, amount, newStatus,
				sourceAccountId, destinationAccountId, newFailureReason, now);
	}

	public TransactionFinalizationDecision calculateOutcome(Map<String, Account> lockedAccounts) {
		if (status != TransactionStatus.PENDING) {
			throw new IllegalStateException("Only pending transactions can be finalized");
		}
		Map<String, Long> balances = new LinkedHashMap<>();
		switch (type) {
			case CREDIT -> balances.put(destinationAccountId,
					Math.addExact(requiredAccount(lockedAccounts, destinationAccountId).balance(), amount));
			case DEBIT -> {
				long sourceBalance = requiredAccount(lockedAccounts, sourceAccountId).balance();
				if (sourceBalance < amount) {
					return TransactionFinalizationDecision.insufficientBalance();
				}
				balances.put(sourceAccountId, sourceBalance - amount);
			}
			case TRANSFER -> {
				long sourceBalance = requiredAccount(lockedAccounts, sourceAccountId).balance();
				if (sourceBalance < amount) {
					return TransactionFinalizationDecision.insufficientBalance();
				}
				long destinationBalance = requiredAccount(lockedAccounts, destinationAccountId).balance();
				balances.put(sourceAccountId, sourceBalance - amount);
				balances.put(destinationAccountId, Math.addExact(destinationBalance, amount));
			}
		}
		return TransactionFinalizationDecision.finalized(balances);
	}

	private static Account requiredAccount(Map<String, Account> accounts, String accountId) {
		Account account = accounts.get(accountId);
		if (account == null) {
			throw new IllegalArgumentException("Missing locked account: " + accountId);
		}
		return account;
	}

	private static void validateAccountReferences(TransactionType type, String source, String destination) {
		if ((source != null && source.length() > 128) || (destination != null && destination.length() > 128)) {
			throw new IllegalArgumentException("Account IDs cannot exceed 128 characters");
		}
		switch (type) {
			case CREDIT -> {
				if (source != null || destination == null || destination.isBlank()) {
					throw new IllegalArgumentException("A credit requires only a destination account");
				}
			}
			case DEBIT -> {
				if (destination != null || source == null || source.isBlank()) {
					throw new IllegalArgumentException("A debit requires only a source account");
				}
			}
			case TRANSFER -> {
				if (source == null || source.isBlank() || destination == null || destination.isBlank()) {
					throw new IllegalArgumentException("A transfer requires source and destination accounts");
				}
				if (source.equals(destination)) {
					throw new IllegalArgumentException("Source and destination accounts must differ");
				}
			}
		}
	}
}
