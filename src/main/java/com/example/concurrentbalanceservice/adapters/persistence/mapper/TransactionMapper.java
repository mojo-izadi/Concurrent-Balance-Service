package com.example.concurrentbalanceservice.adapters.persistence.mapper;

import com.example.concurrentbalanceservice.adapters.persistence.entity.TransactionJpaEntity;
import com.example.concurrentbalanceservice.domain.Transaction;

public class TransactionMapper {
	public Transaction toDomain(TransactionJpaEntity entity) {
		return new Transaction(entity.getTransactionId(), entity.getType(), entity.getAmount(),
				entity.getStatus(), entity.getSourceAccountId(), entity.getDestinationAccountId(),
				entity.getFailureReason(), entity.getUpdatedAt());
	}
}
