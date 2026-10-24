package com.example.concurrentbalanceservice.repository.mapper;

import com.example.concurrentbalanceservice.domain.Transaction;
import com.example.concurrentbalanceservice.repository.entity.TransactionJpaEntity;

public class TransactionMapper {
	public Transaction toDomain(TransactionJpaEntity entity) {
		return new Transaction(entity.getTransactionId(), entity.getType(), entity.getAmount(),
				entity.getStatus(), entity.getSourceAccountId(), entity.getDestinationAccountId(),
				entity.getFailureReason(), entity.getUpdatedAt());
	}
}
