package com.example.concurrentbalanceservice.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts")
public class AccountJpaEntity {
	@Id
	@Column(name = "account_id", nullable = false, length = 128)
	private String accountId;

	@Column(name = "balance", nullable = false)
	private long balance;

	protected AccountJpaEntity() {
	}

	public long getBalance() {
		return balance;
	}
}
