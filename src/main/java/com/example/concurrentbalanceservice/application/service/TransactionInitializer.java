package com.example.concurrentbalanceservice.application.service;

import com.example.concurrentbalanceservice.application.exception.AccountNotFoundException;
import com.example.concurrentbalanceservice.application.exception.InvalidOperationException;
import com.example.concurrentbalanceservice.application.port.BalanceRepository;
import com.example.concurrentbalanceservice.application.port.JobExecutor;
import com.example.concurrentbalanceservice.domain.Transaction;
import com.example.concurrentbalanceservice.domain.OperationType;
import java.time.Clock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TransactionInitializer {
	private static final Logger LOGGER = Logger.getLogger(TransactionInitializer.class.getName());

	private final BalanceRepository balanceRepository;
	private final JobExecutor jobExecutor;
	private final Clock clock;

	public TransactionInitializer(BalanceRepository balanceRepository, JobExecutor jobExecutor, Clock clock) {
		this.balanceRepository = balanceRepository;
		this.jobExecutor = jobExecutor;
		this.clock = clock;
	}

	public void credit(String accountId, long amount, String transactionId) {
		initialize(OperationType.CREDIT, null, accountId, amount, transactionId);
	}

	public void debit(String accountId, long amount, String transactionId) {
		initialize(OperationType.DEBIT, accountId, null, amount, transactionId);
	}

	public void transfer(String sourceAccountId, String destinationAccountId, long amount, String transactionId) {
		initialize(OperationType.TRANSFER, sourceAccountId, destinationAccountId, amount, transactionId);
	}

	private void initialize(OperationType type, String sourceAccountId, String destinationAccountId,
			long amount, String transactionId) {
		Transaction pendingTransaction;
		try {
			pendingTransaction = Transaction.pending(transactionId, type, amount,
					sourceAccountId, destinationAccountId, clock.instant());
		}
		catch (IllegalArgumentException exception) {
			throw new InvalidOperationException(exception.getMessage());
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
			jobExecutor.execute(transactionId);
		}
		catch (RuntimeException exception) {
			LOGGER.log(Level.WARNING, "Could not dispatch accepted operation {0}; recovery will retry it", transactionId);
			LOGGER.log(Level.FINE, "Dispatch failure for operation " + transactionId, exception);
		}
	}
}
