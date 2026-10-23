package com.example.concurrentbalanceservice.adapters.http;

public record OperationRequest(String transactionId, Long amount) {
}
