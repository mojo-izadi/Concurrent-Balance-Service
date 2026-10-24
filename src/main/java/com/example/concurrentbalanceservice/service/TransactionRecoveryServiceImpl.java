package com.example.concurrentbalanceservice.service;

import com.example.concurrentbalanceservice.execution.JobExecutor;
import com.example.concurrentbalanceservice.repository.BalanceRepository;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TransactionRecoveryServiceImpl implements TransactionRecoveryService {
	private static final Logger LOGGER = Logger.getLogger(TransactionRecoveryServiceImpl.class.getName());
	private final BalanceRepository balanceRepository;
	private final JobExecutor jobExecutor;
	private final Duration minimumPendingAge;

	public TransactionRecoveryServiceImpl(BalanceRepository balanceRepository, JobExecutor jobExecutor,
			Duration minimumPendingAge) {
		if (minimumPendingAge.isNegative()) {
			throw new IllegalArgumentException("Minimum pending age must be nonnegative");
		}
		this.balanceRepository = balanceRepository;
		this.jobExecutor = jobExecutor;
		this.minimumPendingAge = minimumPendingAge;
	}

	@Override
	public void recoverPendingTransactions() {
		for (String transactionId : balanceRepository.findStalePendingTransactionIds(minimumPendingAge)) {
			try {
				jobExecutor.submitFinalization(transactionId);
			}
			catch (RuntimeException exception) {
				LOGGER.log(Level.WARNING, "Could not schedule recovery for transaction " + transactionId, exception);
			}
		}
	}
}
