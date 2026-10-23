package com.example.concurrentbalanceservice.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AccountTest {
	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t"})
	void rejectsMissingIdentifiers(String id) {
		assertThatIllegalArgumentException().isThrownBy(() -> new Account(id, 0));
	}

	@Test
	void enforcesBalanceAndIdentifierLimits() {
		assertThatIllegalArgumentException().isThrownBy(() -> new Account("A", -1));
		assertThatIllegalArgumentException().isThrownBy(() -> new Account("A".repeat(129), 0));
		assertThat(new Account("A".repeat(128), 0).balance()).isZero();
		assertThat(new Account("A", Long.MAX_VALUE).balance()).isEqualTo(Long.MAX_VALUE);
	}
}
