package com.example.concurrentbalanceservice.application.port;

import com.example.concurrentbalanceservice.domain.Account;
import com.example.concurrentbalanceservice.domain.Transaction;
import java.util.Map;

public record ExecutionSnapshot(Transaction transaction, Map<String, Account> lockedAccounts) {
	public ExecutionSnapshot {
		lockedAccounts = Map.copyOf(lockedAccounts);
	}
}
