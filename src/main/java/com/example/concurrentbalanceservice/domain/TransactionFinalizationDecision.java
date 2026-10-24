package com.example.concurrentbalanceservice.domain;

import java.util.Map;

public record TransactionFinalizationDecision(TransactionStatus status, String failureReason, Map<String, Long> resultingBalances) {
	public TransactionFinalizationDecision {
		resultingBalances = Map.copyOf(resultingBalances);
	}

	public static TransactionFinalizationDecision finalized(Map<String, Long> resultingBalances) {
		return new TransactionFinalizationDecision(TransactionStatus.FINALIZED, null, resultingBalances);
	}

	public static TransactionFinalizationDecision insufficientBalance() {
		return new TransactionFinalizationDecision(TransactionStatus.FAILED,
				FailureReason.INSUFFICIENT_BALANCE.name(), Map.of());
	}
}
