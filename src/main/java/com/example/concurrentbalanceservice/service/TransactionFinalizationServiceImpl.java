package com.example.concurrentbalanceservice.service;

import com.example.concurrentbalanceservice.domain.TransactionFinalizationDecision;
import com.example.concurrentbalanceservice.domain.FailureReason;
import com.example.concurrentbalanceservice.domain.TransactionStatus;
import com.example.concurrentbalanceservice.domain.Transaction;
import com.example.concurrentbalanceservice.repository.BalanceRepository;
import com.example.concurrentbalanceservice.repository.TransactionFinalizationSnapshot;
import com.example.concurrentbalanceservice.service.exception.NonRetryableFinalizationException;
import com.example.concurrentbalanceservice.service.exception.TransactionAlreadyFinalizedException;
import com.example.concurrentbalanceservice.service.exception.TransactionNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TransactionFinalizationServiceImpl implements TransactionFinalizationService {
	private static final Logger LOGGER = Logger.getLogger(TransactionFinalizationServiceImpl.class.getName());

	private final BalanceRepository balanceRepository;
	private final DatabaseTransactionBoundary databaseTransactionBoundary;
	private final Clock clock;
	private final int maxRetries;
	private final Duration retryDelay;

	public TransactionFinalizationServiceImpl(BalanceRepository balanceRepository,
			DatabaseTransactionBoundary databaseTransactionBoundary, Clock clock,
			@Value("${balance.finalization.max-retries:3}") int maxRetries,
			@Value("${balance.finalization.retry-delay:100ms}") Duration retryDelay) {
		if (maxRetries < 0 || retryDelay.isNegative()) {
			throw new IllegalArgumentException("Retry count and delay must be nonnegative");
		}
		this.balanceRepository = balanceRepository;
		this.databaseTransactionBoundary = databaseTransactionBoundary;
		this.clock = clock;
		this.maxRetries = maxRetries;
		this.retryDelay = retryDelay;
	}

	@Override
	public void finalizeTransaction(String transactionId) {
		for (int attempt = 0; attempt <= maxRetries; attempt++) {
			try {
				databaseTransactionBoundary.inNewDatabaseTransaction(() -> {
					finalizeAttempt(transactionId);
					return null;
				});
				return;
			}
			catch (TransactionAlreadyFinalizedException | TransactionNotFoundException exception) {
				return;
			}
			catch (NonRetryableFinalizationException exception) {
				LOGGER.log(Level.WARNING, "Non-retryable finalization error for transaction " + transactionId, exception);
				finalizeFailedTransaction(transactionId);
				return;
			}
			catch (RuntimeException exception) {
				if (attempt < maxRetries) {
					if (!pauseBeforeRetry(transactionId, attempt + 1)) {
						return;
					}
					continue;
				}
				LOGGER.log(Level.WARNING, "Finalization retries exhausted for transaction " + transactionId, exception);
				finalizeFailedTransaction(transactionId);
				return;
			}
		}
	}

	private void finalizeAttempt(String transactionId) {
		TransactionFinalizationSnapshot snapshot = balanceRepository.lockForTransactionFinalization(transactionId);
		Transaction transaction = snapshot.transaction();
		TransactionFinalizationDecision decision;
		try {
			decision = transaction.calculateOutcome(snapshot.lockedAccounts());
		}
		catch (IllegalArgumentException | IllegalStateException | ArithmeticException exception) {
			throw new NonRetryableFinalizationException("Persisted transaction could not be finalized", exception);
		}
		for (var balance : decision.resultingBalances().entrySet()) {
			balanceRepository.updateBalance(balance.getKey(), balance.getValue());
		}
		balanceRepository.updateOutcome(transaction.transactionId(), decision.status(),
				decision.failureReason(), clock.instant());
	}

	private void finalizeFailedTransaction(String transactionId) {
		try {
			databaseTransactionBoundary.inNewDatabaseTransaction(() -> {
				balanceRepository.lockForTransactionFinalization(transactionId);
				balanceRepository.updateOutcome(transactionId, TransactionStatus.FAILED,
						FailureReason.FINALIZATION_ERROR.name(), clock.instant());
				return null;
			});
		}
		catch (TransactionAlreadyFinalizedException | TransactionNotFoundException ignored) {
			// Another worker completed it, or the transaction no longer exists.
		}
		catch (RuntimeException exception) {
			LOGGER.log(Level.WARNING, "Could not finalize failed transaction " + transactionId
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
			LOGGER.log(Level.FINE, "Retry wait interrupted for transaction " + transactionId + " before retry " + retryNumber);
			return false;
		}
	}
}
