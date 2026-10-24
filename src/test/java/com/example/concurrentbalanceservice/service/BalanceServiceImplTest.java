package com.example.concurrentbalanceservice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.concurrentbalanceservice.repository.BalanceRepository;
import com.example.concurrentbalanceservice.service.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BalanceServiceImplTest {
	private final TransactionInitializer initializer = mock(TransactionInitializer.class);
	private final BalanceRepository repository = mock(BalanceRepository.class);
	private final BalanceService service = new BalanceServiceImpl(initializer, repository);

	@Test
	void delegatesSubmissionsAndBalanceReads() {
		service.credit("A", 1, "credit");
		service.debit("A", 2, "debit");
		service.transfer("A", "B", 3, "transfer");
		verify(initializer).credit("A", 1, "credit");
		verify(initializer).debit("A", 2, "debit");
		verify(initializer).transfer("A", "B", 3, "transfer");
		when(repository.getBalance("A")).thenReturn(100L);
		assertThat(service.getBalance("A")).isEqualTo(100);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t"})
	void rejectsInvalidBalanceReadIds(String id) {
		assertThatThrownBy(() -> service.getBalance(id)).isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(repository);
	}

	@Test
	void rejectsOverlongBalanceReadId() {
		assertThatThrownBy(() -> service.getBalance("A".repeat(129))).isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(repository);
	}
}
