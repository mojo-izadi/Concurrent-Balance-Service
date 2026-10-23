package com.example.concurrentbalanceservice.adapters.http;

public record TransferRequest(String transactionId, String sourceAccountId,
		String destinationAccountId, Long amount) {
}
