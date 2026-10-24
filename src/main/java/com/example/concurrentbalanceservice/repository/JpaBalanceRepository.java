package com.example.concurrentbalanceservice.repository;

import com.example.concurrentbalanceservice.domain.Account;
import com.example.concurrentbalanceservice.domain.TransactionStatus;
import com.example.concurrentbalanceservice.domain.Transaction;
import com.example.concurrentbalanceservice.repository.entity.AccountJpaEntity;
import com.example.concurrentbalanceservice.repository.mapper.TransactionMapper;
import com.example.concurrentbalanceservice.service.exception.AccountNotFoundException;
import com.example.concurrentbalanceservice.service.exception.DuplicateTransactionIdException;
import com.example.concurrentbalanceservice.service.exception.TransactionAlreadyFinalizedException;
import com.example.concurrentbalanceservice.service.exception.TransactionNotFoundException;
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
		return accountRepository.findById(accountId)
				.map(AccountJpaEntity::getBalance)
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
	public TransactionFinalizationSnapshot lockForTransactionFinalization(String transactionId) {
		Transaction transaction = transactionRepository.findById(transactionId)
				.map(transactionMapper::toDomain)
				.orElseThrow(() -> new TransactionNotFoundException(transactionId));
		Map<String, Account> lockedAccounts = new LinkedHashMap<>();
		// Every worker takes account locks in the same order, including opposite-direction transfers.
		List<String> accountIds = Stream.of(transaction.sourceAccountId(), transaction.destinationAccountId())
				.filter(Objects::nonNull).distinct().sorted().toList();
		for (String accountId : accountIds) {
			long balance = accountRepository.lockBalanceForFinalization(accountId)
					.orElseThrow(() -> new AccountNotFoundException(accountId));
			lockedAccounts.put(accountId, new Account(accountId, balance));
		}

		// Scalar native queries bypass JPA's entity cache after a competing worker releases its locks.
		String status = transactionRepository.findStatus(transactionId)
				.orElseThrow(() -> new TransactionNotFoundException(transactionId));
		if (!TransactionStatus.PENDING.name().equals(status)) {
			throw new TransactionAlreadyFinalizedException(transactionId);
		}
		return new TransactionFinalizationSnapshot(transaction, lockedAccounts);
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
	public void updateOutcome(String transactionId, TransactionStatus status, String failureReason, Instant updatedAt) {
		if (transactionRepository.updateOutcome(transactionId, status.name(), failureReason, updatedAt) != 1) {
			if (!transactionRepository.existsById(transactionId)) {
				throw new TransactionNotFoundException(transactionId);
			}
			throw new TransactionAlreadyFinalizedException(transactionId);
		}
	}

	@Override
	public List<String> findStalePendingTransactionIds(Duration minimumAge) {
		return transactionRepository.findStalePendingIds(Math.max(0, minimumAge.toMillis()));
	}
}
