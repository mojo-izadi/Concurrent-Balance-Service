package com.example.concurrentbalanceservice.application.service;

import com.example.concurrentbalanceservice.application.port.BalanceRepository;
import com.example.concurrentbalanceservice.application.port.JobExecutor;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OperationRecoveryService {
	private static final Logger LOGGER = Logger.getLogger(OperationRecoveryService.class.getName());
	private final BalanceRepository balanceRepository;
	private final JobExecutor jobExecutor;
	private final Duration minimumPendingAge;

	public OperationRecoveryService(BalanceRepository balanceRepository, JobExecutor jobExecutor,
			Duration minimumPendingAge) {
		if (minimumPendingAge.isNegative()) {
			throw new IllegalArgumentException("Minimum pending age must be nonnegative");
		}
		this.balanceRepository = balanceRepository;
		this.jobExecutor = jobExecutor;
		this.minimumPendingAge = minimumPendingAge;
	}

	public void recoverPendingOperations() {
		for (String transactionId : balanceRepository.findStalePendingTransactionIds(minimumPendingAge)) {
			try {
				jobExecutor.execute(transactionId);
			}
			catch (RuntimeException exception) {
				LOGGER.log(Level.WARNING, "Could not schedule recovery for operation " + transactionId, exception);
			}
		}
	}
}
