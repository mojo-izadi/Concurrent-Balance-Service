package com.example.concurrentbalanceservice.domain;

import java.util.Map;

public record ExecutionDecision(OperationStatus status, String failureReason, Map<String, Long> resultingBalances) {
	public ExecutionDecision {
		resultingBalances = Map.copyOf(resultingBalances);
	}

	public static ExecutionDecision finished(Map<String, Long> resultingBalances) {
		return new ExecutionDecision(OperationStatus.FINISHED, null, resultingBalances);
	}

	public static ExecutionDecision insufficientBalance() {
		return new ExecutionDecision(OperationStatus.FAILED,
				FailureReason.INSUFFICIENT_BALANCE.name(), Map.of());
	}
}
