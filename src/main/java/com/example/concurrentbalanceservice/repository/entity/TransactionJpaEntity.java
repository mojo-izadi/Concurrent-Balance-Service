package com.example.concurrentbalanceservice.repository.entity;

import com.example.concurrentbalanceservice.domain.TransactionStatus;
import com.example.concurrentbalanceservice.domain.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "transactions")
public class TransactionJpaEntity {
	@Id
	@Column(name = "transaction_id", nullable = false, length = 128, updatable = false)
	private String transactionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "transaction_type", nullable = false, length = 16, updatable = false)
	private TransactionType type;

	@Column(name = "amount", nullable = false, updatable = false)
	private long amount;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private TransactionStatus status;

	@Column(name = "source_account_id", length = 128, updatable = false)
	private String sourceAccountId;

	@Column(name = "destination_account_id", length = 128, updatable = false)
	private String destinationAccountId;

	@Column(name = "failure_reason", length = 64)
	private String failureReason;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected TransactionJpaEntity() {
	}

	public String getTransactionId() {
		return transactionId;
	}

	public TransactionType getType() {
		return type;
	}

	public long getAmount() {
		return amount;
	}

	public TransactionStatus getStatus() {
		return status;
	}

	public String getSourceAccountId() {
		return sourceAccountId;
	}

	public String getDestinationAccountId() {
		return destinationAccountId;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
