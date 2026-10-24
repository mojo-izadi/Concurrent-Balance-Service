package com.example.concurrentbalanceservice.dto;

public record TransactionRequest(String transactionId, Long amount) {
}
