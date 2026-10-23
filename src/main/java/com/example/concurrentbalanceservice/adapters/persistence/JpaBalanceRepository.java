package com.example.concurrentbalanceservice.adapters.persistence;

import com.example.concurrentbalanceservice.adapters.persistence.mapper.TransactionMapper;
import com.example.concurrentbalanceservice.adapters.persistence.repository.AccountJpaRepository;
import com.example.concurrentbalanceservice.adapters.persistence.repository.TransactionJpaRepository;
import com.example.concurrentbalanceservice.application.exception.AccountNotFoundException;
import com.example.concurrentbalanceservice.application.exception.DuplicateTransactionIdException;
import com.example.concurrentbalanceservice.application.exception.OperationAlreadyFinalizedException;
import com.example.concurrentbalanceservice.application.exception.OperationNotFoundException;
import com.example.concurrentbalanceservice.application.port.BalanceRepository;
import com.example.concurrentbalanceservice.application.port.ExecutionSnapshot;
import com.example.concurrentbalanceservice.domain.Account;
import com.example.concurrentbalanceservice.domain.OperationStatus;
import com.example.concurrentbalanceservice.domain.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaBalanceRepository implements BalanceRepository {
	private final AccountJpaRepository accountRepository;
	private final TransactionJpaRepository transactionRepository;
	private final TransactionMapper transactionMapper = new TransactionMapper();

	public JpaBalanceRepository(AccountJpaRepository accountRepository,
			TransactionJpaRepository transactionRepository) {
		this.accountRepository = accountRepository;
		this.transactionRepository = transactionRepository;
	}

	@Override
	public boolean accountExists(String accountId) {
		return accountRepository.existsById(accountId);
	}

	@Override
	public long getBalance(String accountId) {
		return accountRepository.findBalance(accountId)
				.orElseThrow(() -> new AccountNotFoundException(accountId));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void insertPending(Transaction transaction) {
		int rows = transactionRepository.insertPending(transaction.transactionId(), transaction.type().name(),
				transaction.amount(), transaction.sourceAccountId(), transaction.destinationAccountId(), transaction.updatedAt());
		if (rows == 0) {
			throw new DuplicateTransactionIdException(transaction.transactionId());
		}
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public ExecutionSnapshot lockForTransactionExecution(String transactionId) {
		Transaction transaction = transactionRepository.findById(transactionId)
				.map(transactionMapper::toDomain)
				.orElseThrow(() -> new OperationNotFoundException(transactionId));
		Map<String, Account> lockedAccounts = new LinkedHashMap<>();
		// Every worker takes account locks in the same order, including opposite-direction transfers.
		List<String> accountIds = Stream.of(transaction.sourceAccountId(), transaction.destinationAccountId())
				.filter(Objects::nonNull).distinct().sorted().toList();
		for (String accountId : accountIds) {
			long balance = accountRepository.lockBalanceForExecution(accountId)
					.orElseThrow(() -> new AccountNotFoundException(accountId));
			lockedAccounts.put(accountId, new Account(accountId, balance));
		}

		// Scalar native queries bypass JPA's entity cache after a competing worker releases its locks.
		String status = transactionRepository.findStatus(transactionId)
				.orElseThrow(() -> new OperationNotFoundException(transactionId));
		if (!OperationStatus.PENDING.name().equals(status)) {
			throw new OperationAlreadyFinalizedException(transactionId);
		}
		return new ExecutionSnapshot(transaction, lockedAccounts);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void updateBalance(String accountId, long balance) {
		if (accountRepository.updateBalance(accountId, balance) != 1) {
			throw new AccountNotFoundException(accountId);
		}
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void updateOutcome(String transactionId, OperationStatus status, String failureReason, Instant updatedAt) {
		if (transactionRepository.updateOutcome(transactionId, status.name(), failureReason, updatedAt) != 1) {
			if (transactionRepository.findStatus(transactionId).isEmpty()) {
				throw new OperationNotFoundException(transactionId);
			}
			throw new OperationAlreadyFinalizedException(transactionId);
		}
	}

	@Override
	public List<String> findStalePendingTransactionIds(Duration minimumAge) {
		return transactionRepository.findStalePendingIds(Math.max(0, minimumAge.toMillis()));
	}
}
