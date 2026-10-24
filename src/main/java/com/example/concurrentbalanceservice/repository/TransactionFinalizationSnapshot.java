package com.example.concurrentbalanceservice.repository;

import com.example.concurrentbalanceservice.domain.Account;
import com.example.concurrentbalanceservice.domain.Transaction;
import java.util.Map;

public record TransactionFinalizationSnapshot(Transaction transaction, Map<String, Account> lockedAccounts) {
	public TransactionFinalizationSnapshot {
		lockedAccounts = Map.copyOf(lockedAccounts);
	}
}
