package com.example.concurrentbalanceservice.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TransactionTest {
	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
	private final Map<String, Account> accounts = Map.of("A", new Account("A", 1_000), "B", new Account("B", 500));

	@Test
	void creditsTheDestination() {
		assertThat(pending(OperationType.CREDIT, 500).apply(accounts))
				.isEqualTo(ExecutionDecision.finished(Map.of("B", 1_000L)));
	}

	@Test
	void debitsTheSourceAndAllowsAnExactBalanceDebit() {
		assertThat(pending(OperationType.DEBIT, 700).apply(accounts).resultingBalances()).containsExactlyEntriesOf(Map.of("A", 300L));
		assertThat(pending(OperationType.DEBIT, 1_000).apply(accounts).resultingBalances()).containsEntry("A", 0L);
	}

	@Test
	void transfersEqualAmountsWithoutMutatingInput() {
		assertThat(pending(OperationType.TRANSFER, 300).apply(accounts))
				.isEqualTo(ExecutionDecision.finished(Map.of("A", 700L, "B", 800L)));
		assertThat(accounts.get("A").balance()).isEqualTo(1_000);
		assertThat(accounts.get("B").balance()).isEqualTo(500);
	}

	@ParameterizedTest
	@EnumSource(value = OperationType.class, names = {"DEBIT", "TRANSFER"})
	void insufficientFundsProduceNoBalanceChanges(OperationType type) {
		assertThat(pending(type, 1_001).apply(accounts)).isEqualTo(ExecutionDecision.insufficientBalance());
	}

	@ParameterizedTest
	@EnumSource(value = OperationType.class, names = {"CREDIT", "TRANSFER"})
	void overflowFailsBeforeReturningAnyBalanceChanges(OperationType type) {
		var overflowing = Map.of("A", new Account("A", 100), "B", new Account("B", Long.MAX_VALUE));
		assertThatThrownBy(() -> pending(type, 1).apply(overflowing)).isInstanceOf(ArithmeticException.class);
	}

	@Test
	void acceptsTheLargestRepresentableBalance() {
		assertThat(pending(OperationType.CREDIT, Long.MAX_VALUE).apply(Map.of("B", new Account("B", 0)))
				.resultingBalances()).containsEntry("B", Long.MAX_VALUE);
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1, Long.MIN_VALUE})
	void rejectsNonpositiveAmounts(long amount) {
		assertThatIllegalArgumentException().isThrownBy(() -> pending(OperationType.CREDIT, amount));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t"})
	void rejectsMissingTransactionIds(String id) {
		assertThatIllegalArgumentException().isThrownBy(() -> Transaction.pending(id, OperationType.CREDIT, 1, null, "B", NOW));
	}

	@ParameterizedTest
	@MethodSource("invalidReferences")
	void rejectsInvalidAccountReferences(OperationType type, String source, String destination) {
		assertThatIllegalArgumentException().isThrownBy(() -> Transaction.pending("tx", type, 1, source, destination, NOW));
	}

	static Stream<Arguments> invalidReferences() {
		return Stream.of(
				Arguments.of(OperationType.CREDIT, "A", "B"),
				Arguments.of(OperationType.CREDIT, null, null),
				Arguments.of(OperationType.CREDIT, null, " "),
				Arguments.of(OperationType.DEBIT, "A", "B"),
				Arguments.of(OperationType.DEBIT, null, null),
				Arguments.of(OperationType.DEBIT, " ", null),
				Arguments.of(OperationType.TRANSFER, "A", "A"),
				Arguments.of(OperationType.TRANSFER, null, "B"),
				Arguments.of(OperationType.TRANSFER, "A", ""),
				Arguments.of(OperationType.TRANSFER, "A".repeat(129), "B"),
				Arguments.of(OperationType.CREDIT, null, "B".repeat(129)));
	}

	@Test
	void enforcesIdentifierLimitsAndRequiredMetadata() {
		assertThatIllegalArgumentException().isThrownBy(() -> Transaction.pending("x".repeat(129), OperationType.CREDIT, 1, null, "B", NOW));
		assertThat(Transaction.pending("x".repeat(128), OperationType.CREDIT, 1, null, "B".repeat(128), NOW)).isNotNull();
		assertThatIllegalArgumentException().isThrownBy(() -> Transaction.pending("tx", null, 1, null, "B", NOW));
		assertThatIllegalArgumentException().isThrownBy(() -> Transaction.pending("tx", OperationType.CREDIT, 1, null, "B", null));
		assertThatIllegalArgumentException().isThrownBy(() -> pending(OperationType.CREDIT, 1).withStatus(null, null, NOW));
	}

	@Test
	void enforcesFailureReasonAndTerminalStateRules() {
		var transaction = pending(OperationType.CREDIT, 1);
		assertThatIllegalArgumentException().isThrownBy(() -> transaction.withStatus(OperationStatus.FAILED, null, NOW));
		assertThatIllegalArgumentException().isThrownBy(() -> transaction.withStatus(OperationStatus.FAILED, " ", NOW));
		assertThatIllegalArgumentException().isThrownBy(() -> transaction.withStatus(OperationStatus.FINISHED, "failure", NOW));
		for (var status : new OperationStatus[]{OperationStatus.FINISHED, OperationStatus.FAILED}) {
			var terminal = transaction.withStatus(status, status == OperationStatus.FAILED ? "failure" : null, NOW);
			assertThatIllegalStateException().isThrownBy(() -> terminal.apply(accounts));
		}
	}

	@Test
	void requiresLockedAccountsAndReturnsImmutableDecisions() {
		assertThatIllegalArgumentException().isThrownBy(() -> pending(OperationType.CREDIT, 1).apply(Map.of()));
		var decision = pending(OperationType.CREDIT, 1).apply(accounts);
		assertThatThrownBy(() -> decision.resultingBalances().put("B", 0L)).isInstanceOf(UnsupportedOperationException.class);
	}

	private Transaction pending(OperationType type, long amount) {
		return Transaction.pending("tx", type, amount, type == OperationType.CREDIT ? null : "A",
				type == OperationType.DEBIT ? null : "B", NOW);
	}
}
