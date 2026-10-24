package com.example.concurrentbalanceservice.dto;

public record TransferRequest(String transactionId, String sourceAccountId,
		String destinationAccountId, Long amount) {
}
