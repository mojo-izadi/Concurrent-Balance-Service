package com.example.concurrentbalanceservice.application.service;

import com.example.concurrentbalanceservice.application.exception.NonRetryableExecutionException;
import com.example.concurrentbalanceservice.application.exception.OperationAlreadyFinalizedException;
import com.example.concurrentbalanceservice.application.exception.OperationNotFoundException;
import com.example.concurrentbalanceservice.application.port.BalanceRepository;
import com.example.concurrentbalanceservice.application.port.ExecutionSnapshot;
import com.example.concurrentbalanceservice.application.port.TransactionBoundary;
import com.example.concurrentbalanceservice.domain.Transaction;
import com.example.concurrentbalanceservice.domain.ExecutionDecision;
import com.example.concurrentbalanceservice.domain.FailureReason;
import com.example.concurrentbalanceservice.domain.OperationStatus;
import java.time.Clock;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OperationExecutionService {
	private static final Logger LOGGER = Logger.getLogger(OperationExecutionService.class.getName());

	private final BalanceRepository balanceRepository;
	private final TransactionBoundary transactionBoundary;
	private final Clock clock;
	private final int maxRetries;
	private final Duration retryDelay;

	public OperationExecutionService(BalanceRepository balanceRepository,
			TransactionBoundary transactionBoundary, Clock clock, int maxRetries, Duration retryDelay) {
		if (maxRetries < 0 || retryDelay.isNegative()) {
			throw new IllegalArgumentException("Retry count and delay must be nonnegative");
		}
		this.balanceRepository = balanceRepository;
		this.transactionBoundary = transactionBoundary;
		this.clock = clock;
		this.maxRetries = maxRetries;
		this.retryDelay = retryDelay;
	}

	public void execute(String transactionId) {
		for (int attempt = 0; attempt <= maxRetries; attempt++) {
			try {
				transactionBoundary.inNewTransaction(() -> {
					executeAttempt(transactionId);
					return null;
				});
				return;
			}
			catch (OperationAlreadyFinalizedException | OperationNotFoundException exception) {
				return;
			}
			catch (NonRetryableExecutionException exception) {
				LOGGER.log(Level.WARNING, "Non-retryable execution error for operation " + transactionId, exception);
				finalizeFailure(transactionId);
				return;
			}
			catch (RuntimeException exception) {
				if (attempt < maxRetries) {
					if (!pauseBeforeRetry(transactionId, attempt + 1)) {
						return;
					}
					continue;
				}
				LOGGER.log(Level.WARNING, "Execution retries exhausted for operation " + transactionId, exception);
				finalizeFailure(transactionId);
				return;
			}
		}
	}

	private void executeAttempt(String transactionId) {
		ExecutionSnapshot snapshot = balanceRepository.lockForTransactionExecution(transactionId);
		Transaction transaction = snapshot.transaction();
		ExecutionDecision decision;
		try {
			decision = transaction.apply(snapshot.lockedAccounts());
		}
		catch (IllegalArgumentException | IllegalStateException | ArithmeticException exception) {
			throw new NonRetryableExecutionException("Persisted operation could not be applied", exception);
		}
		for (var balance : decision.resultingBalances().entrySet()) {
			balanceRepository.updateBalance(balance.getKey(), balance.getValue());
		}
		balanceRepository.updateOutcome(transaction.transactionId(), decision.status(),
				decision.failureReason(), clock.instant());
	}

	private void finalizeFailure(String transactionId) {
		try {
			transactionBoundary.inNewTransaction(() -> {
				balanceRepository.lockForTransactionExecution(transactionId);
				balanceRepository.updateOutcome(transactionId, OperationStatus.FAILED,
						FailureReason.EXECUTION_ERROR.name(), clock.instant());
				return null;
			});
		}
		catch (OperationAlreadyFinalizedException | OperationNotFoundException ignored) {
			// Another worker completed it, or the operation no longer exists.
		}
		catch (RuntimeException exception) {
			LOGGER.log(Level.WARNING, "Could not finalize failed operation " + transactionId
					+ "; it remains eligible for recovery", exception);
		}
	}

	private boolean pauseBeforeRetry(String transactionId, int retryNumber) {
		if (retryDelay.isZero()) {
			return true;
		}
		try {
			Thread.sleep(retryDelay);
			return true;
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			LOGGER.log(Level.FINE, "Retry wait interrupted for operation " + transactionId + " before retry " + retryNumber);
			return false;
		}
	}
}
