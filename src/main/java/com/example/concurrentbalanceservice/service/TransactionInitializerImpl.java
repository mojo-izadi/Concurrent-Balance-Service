package com.example.concurrentbalanceservice.service;

import com.example.concurrentbalanceservice.domain.TransactionType;
import com.example.concurrentbalanceservice.domain.Transaction;
import com.example.concurrentbalanceservice.execution.JobExecutor;
import com.example.concurrentbalanceservice.repository.BalanceRepository;
import com.example.concurrentbalanceservice.service.exception.AccountNotFoundException;
import com.example.concurrentbalanceservice.service.exception.InvalidRequestException;
import java.time.Clock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TransactionInitializerImpl implements TransactionInitializer {
	private static final Logger LOGGER = Logger.getLogger(TransactionInitializerImpl.class.getName());

	private final BalanceRepository balanceRepository;
	private final JobExecutor jobExecutor;
	private final Clock clock;

	public TransactionInitializerImpl(BalanceRepository balanceRepository, JobExecutor jobExecutor, Clock clock) {
		this.balanceRepository = balanceRepository;
		this.jobExecutor = jobExecutor;
		this.clock = clock;
	}

	@Override
	public void credit(String accountId, long amount, String transactionId) {
		initialize(TransactionType.CREDIT, null, accountId, amount, transactionId);
	}

	@Override
	public void debit(String accountId, long amount, String transactionId) {
		initialize(TransactionType.DEBIT, accountId, null, amount, transactionId);
	}

	@Override
	public void transfer(String sourceAccountId, String destinationAccountId, long amount, String transactionId) {
		initialize(TransactionType.TRANSFER, sourceAccountId, destinationAccountId, amount, transactionId);
	}

	private void initialize(TransactionType type, String sourceAccountId, String destinationAccountId,
			long amount, String transactionId) {
		Transaction pendingTransaction;
		try {
			pendingTransaction = Transaction.pending(transactionId, type, amount,
					sourceAccountId, destinationAccountId, clock.instant());
		}
		catch (IllegalArgumentException exception) {
			throw new InvalidRequestException(exception.getMessage());
		}

		if (sourceAccountId != null && !balanceRepository.accountExists(sourceAccountId)) {
			throw new AccountNotFoundException(sourceAccountId);
		}
		if (destinationAccountId != null && !balanceRepository.accountExists(destinationAccountId)) {
			throw new AccountNotFoundException(destinationAccountId);
		}
		// The persistence port commits admission before returning; its unique key arbitrates duplicate IDs.
		balanceRepository.insertPending(pendingTransaction);

		// The committed PENDING transaction is the accepted work item; dispatch failure is recoverable by scanning it later.
		try {
			jobExecutor.submitFinalization(transactionId);
		}
		catch (RuntimeException exception) {
			LOGGER.log(Level.WARNING, "Could not dispatch accepted transaction {0}; recovery will retry it", transactionId);
			LOGGER.log(Level.FINE, "Dispatch failure for transaction " + transactionId, exception);
		}
	}
}
