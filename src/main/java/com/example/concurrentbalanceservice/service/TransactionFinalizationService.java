package com.example.concurrentbalanceservice.service;

public interface TransactionFinalizationService {
	void finalizeTransaction(String transactionId);
}
