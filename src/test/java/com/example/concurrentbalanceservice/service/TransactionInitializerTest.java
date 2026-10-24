package com.example.concurrentbalanceservice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.concurrentbalanceservice.domain.TransactionType;
import com.example.concurrentbalanceservice.domain.Transaction;
import com.example.concurrentbalanceservice.execution.JobExecutor;
import com.example.concurrentbalanceservice.repository.BalanceRepository;
import com.example.concurrentbalanceservice.service.exception.AccountNotFoundException;
import com.example.concurrentbalanceservice.service.exception.DuplicateTransactionIdException;
import com.example.concurrentbalanceservice.service.exception.InvalidRequestException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TransactionInitializerTest {
	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
	private final BalanceRepository repository = mock(BalanceRepository.class);
	private final JobExecutor executor = mock(JobExecutor.class);
	private final TransactionInitializer initializer = new TransactionInitializerImpl(repository, executor, Clock.fixed(NOW, ZoneOffset.UTC));

	@BeforeEach
	void knownAccounts() {
		when(repository.accountExists(anyString())).thenReturn(true);
	}

	@ParameterizedTest
	@EnumSource(TransactionType.class)
	void insertsPendingWorkBeforeDispatch(TransactionType type) {
		submit(type, "A", "B", 100, "tx");
		var order = inOrder(repository, executor);
		order.verify(repository).insertPending(Transaction.pending("tx", type, 100,
				type == TransactionType.CREDIT ? null : "A", type == TransactionType.DEBIT ? null : "B", NOW));
		order.verify(executor).submitFinalization("tx");
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1, Long.MIN_VALUE})
	void rejectsInvalidAmountsBeforeDatabaseAccess(long amount) {
		for (var type : TransactionType.values()) {
			assertThatThrownBy(() -> submit(type, "A", "B", amount, "tx")).isInstanceOf(InvalidRequestException.class);
		}
		verifyNoInteractions(executor);
		verify(repository, never()).accountExists(any());
		verify(repository, never()).insertPending(any());
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t"})
	void rejectsInvalidIdentifiers(String id) {
		assertThatThrownBy(() -> initializer.credit("A", 1, id)).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> initializer.credit(id, 1, "tx")).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> initializer.debit(id, 1, "tx")).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> initializer.transfer(id, "B", 1, "tx")).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> initializer.transfer("A", id, 1, "tx")).isInstanceOf(InvalidRequestException.class);
		verify(repository, never()).insertPending(any());
		verifyNoInteractions(executor);
	}

	@Test
	void rejectsOverlongIdentifiersAndSameAccountTransfers() {
		assertThatThrownBy(() -> initializer.credit("A", 1, "x".repeat(129))).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> initializer.credit("A".repeat(129), 1, "tx")).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> initializer.transfer("A", "A", 1, "tx")).isInstanceOf(InvalidRequestException.class);
		verify(repository, never()).insertPending(any());
		verifyNoInteractions(executor);
	}

	@ParameterizedTest
	@EnumSource(TransactionType.class)
	void unknownAccountsAreRejectedWithoutAdmission(TransactionType type) {
		when(repository.accountExists(anyString())).thenReturn(false);
		assertThatThrownBy(() -> submit(type, "A", "B", 1, "tx")).isInstanceOf(AccountNotFoundException.class);
		verify(repository, never()).insertPending(any());
		verifyNoInteractions(executor);
	}

	@Test
	void checksBothTransferAccounts() {
		when(repository.accountExists("B")).thenReturn(false);
		assertThatThrownBy(() -> initializer.transfer("A", "B", 1, "tx"))
				.isInstanceOf(AccountNotFoundException.class).hasMessageContaining("B");
		verify(repository, never()).insertPending(any());
	}

	@Test
	void duplicateAdmissionNeverDispatches() {
		doThrow(new DuplicateTransactionIdException("tx")).when(repository).insertPending(any());
		assertThatThrownBy(() -> initializer.credit("B", 1, "tx")).isInstanceOf(DuplicateTransactionIdException.class);
		verifyNoInteractions(executor);
	}

	@Test
	void databaseFailureNeverDispatches() {
		doThrow(new IllegalStateException("offline")).when(repository).insertPending(any());
		assertThatThrownBy(() -> initializer.credit("B", 1, "tx")).hasMessage("offline");
		verifyNoInteractions(executor);
	}

	@Test
	void dispatchFailureLeavesAcceptedWorkForRecovery() {
		doThrow(new RejectedExecutionException()).when(executor).submitFinalization("tx");
		assertThatCode(() -> initializer.credit("B", 1, "tx")).doesNotThrowAnyException();
		verify(repository).insertPending(any());
	}

	private void submit(TransactionType type, String source, String destination, long amount, String id) {
		switch (type) {
			case CREDIT -> initializer.credit(destination, amount, id);
			case DEBIT -> initializer.debit(source, amount, id);
			case TRANSFER -> initializer.transfer(source, destination, amount, id);
		}
	}
}
