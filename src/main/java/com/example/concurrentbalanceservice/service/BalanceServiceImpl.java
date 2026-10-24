package com.example.concurrentbalanceservice.service;

import com.example.concurrentbalanceservice.repository.BalanceRepository;
import com.example.concurrentbalanceservice.service.exception.InvalidRequestException;
import org.springframework.stereotype.Component;

@Component
public class BalanceServiceImpl implements BalanceService {
	private final TransactionInitializer transactionInitializer;
	private final BalanceRepository balanceRepository;

	public BalanceServiceImpl(TransactionInitializer transactionInitializer,
			BalanceRepository balanceRepository) {
		this.transactionInitializer = transactionInitializer;
		this.balanceRepository = balanceRepository;
	}

	@Override
	public void credit(String accountId, long amount, String transactionId) {
		transactionInitializer.credit(accountId, amount, transactionId);
	}

	@Override
	public void debit(String accountId, long amount, String transactionId) {
		transactionInitializer.debit(accountId, amount, transactionId);
	}

	@Override
	public void transfer(String sourceAccountId, String destinationAccountId, long amount, String transactionId) {
		transactionInitializer.transfer(sourceAccountId, destinationAccountId, amount, transactionId);
	}

	@Override
	public long getBalance(String accountId) {
		if (accountId == null || accountId.isBlank() || accountId.length() > 128) {
			throw new InvalidRequestException("Account ID must contain between 1 and 128 characters");
		}
		return balanceRepository.getBalance(accountId);
	}
}
