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
		assertThat(pending(TransactionType.CREDIT, 500).calculateOutcome(accounts))
				.isEqualTo(TransactionFinalizationDecision.finalized(Map.of("B", 1_000L)));
	}

	@Test
	void debitsTheSourceAndAllowsAnExactBalanceDebit() {
		assertThat(pending(TransactionType.DEBIT, 700).calculateOutcome(accounts).resultingBalances()).containsExactlyEntriesOf(Map.of("A", 300L));
		assertThat(pending(TransactionType.DEBIT, 1_000).calculateOutcome(accounts).resultingBalances()).containsEntry("A", 0L);
	}

	@Test
	void transfersEqualAmountsWithoutMutatingInput() {
		assertThat(pending(TransactionType.TRANSFER, 300).calculateOutcome(accounts))
				.isEqualTo(TransactionFinalizationDecision.finalized(Map.of("A", 700L, "B", 800L)));
		assertThat(accounts.get("A").balance()).isEqualTo(1_000);
		assertThat(accounts.get("B").balance()).isEqualTo(500);
	}

	@ParameterizedTest
	@EnumSource(value = TransactionType.class, names = {"DEBIT", "TRANSFER"})
	void insufficientFundsProduceNoBalanceChanges(TransactionType type) {
		assertThat(pending(type, 1_001).calculateOutcome(accounts)).isEqualTo(TransactionFinalizationDecision.insufficientBalance());
	}

	@ParameterizedTest
	@EnumSource(value = TransactionType.class, names = {"CREDIT", "TRANSFER"})
	void overflowFailsBeforeReturningAnyBalanceChanges(TransactionType type) {
		var overflowing = Map.of("A", new Account("A", 100), "B", new Account("B", Long.MAX_VALUE));
		assertThatThrownBy(() -> pending(type, 1).calculateOutcome(overflowing)).isInstanceOf(ArithmeticException.class);
	}

	@Test
	void acceptsTheLargestRepresentableBalance() {
		assertThat(pending(TransactionType.CREDIT, Long.MAX_VALUE).calculateOutcome(Map.of("B", new Account("B", 0)))
				.resultingBalances()).containsEntry("B", Long.MAX_VALUE);
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1, Long.MIN_VALUE})
	void rejectsNonpositiveAmounts(long amount) {
		assertThatIllegalArgumentException().isThrownBy(() -> pending(TransactionType.CREDIT, amount));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t"})
	void rejectsMissingTransactionIds(String id) {
		assertThatIllegalArgumentException().isThrownBy(() -> Transaction.pending(id, TransactionType.CREDIT, 1, null, "B", NOW));
	}

	@ParameterizedTest
	@MethodSource("invalidReferences")
	void rejectsInvalidAccountReferences(TransactionType type, String source, String destination) {
		assertThatIllegalArgumentException().isThrownBy(() -> Transaction.pending("tx", type, 1, source, destination, NOW));
	}

	static Stream<Arguments> invalidReferences() {
		return Stream.of(
				Arguments.of(TransactionType.CREDIT, "A", "B"),
				Arguments.of(TransactionType.CREDIT, null, null),
				Arguments.of(TransactionType.CREDIT, null, " "),
				Arguments.of(TransactionType.DEBIT, "A", "B"),
				Arguments.of(TransactionType.DEBIT, null, null),
				Arguments.of(TransactionType.DEBIT, " ", null),
				Arguments.of(TransactionType.TRANSFER, "A", "A"),
				Arguments.of(TransactionType.TRANSFER, null, "B"),
				Arguments.of(TransactionType.TRANSFER, "A", ""),
				Arguments.of(TransactionType.TRANSFER, "A".repeat(129), "B"),
				Arguments.of(TransactionType.CREDIT, null, "B".repeat(129)));
	}

	@Test
	void enforcesIdentifierLimitsAndRequiredMetadata() {
		assertThatIllegalArgumentException().isThrownBy(() -> Transaction.pending("x".repeat(129), TransactionType.CREDIT, 1, null, "B", NOW));
		assertThat(Transaction.pending("x".repeat(128), TransactionType.CREDIT, 1, null, "B".repeat(128), NOW)).isNotNull();
		assertThatIllegalArgumentException().isThrownBy(() -> Transaction.pending("tx", null, 1, null, "B", NOW));
		assertThatIllegalArgumentException().isThrownBy(() -> Transaction.pending("tx", TransactionType.CREDIT, 1, null, "B", null));
		assertThatIllegalArgumentException().isThrownBy(() -> pending(TransactionType.CREDIT, 1).withStatus(null, null, NOW));
	}

	@Test
	void enforcesFailureReasonAndTerminalStateRules() {
		var transaction = pending(TransactionType.CREDIT, 1);
		assertThatIllegalArgumentException().isThrownBy(() -> transaction.withStatus(TransactionStatus.FAILED, null, NOW));
		assertThatIllegalArgumentException().isThrownBy(() -> transaction.withStatus(TransactionStatus.FAILED, " ", NOW));
		assertThatIllegalArgumentException().isThrownBy(() -> transaction.withStatus(TransactionStatus.FINALIZED, "failure", NOW));
		for (var status : new TransactionStatus[]{TransactionStatus.FINALIZED, TransactionStatus.FAILED}) {
			var terminal = transaction.withStatus(status, status == TransactionStatus.FAILED ? "failure" : null, NOW);
			assertThatIllegalStateException().isThrownBy(() -> terminal.calculateOutcome(accounts));
		}
	}

	@Test
	void requiresLockedAccountsAndReturnsImmutableDecisions() {
		assertThatIllegalArgumentException().isThrownBy(() -> pending(TransactionType.CREDIT, 1).calculateOutcome(Map.of()));
		var decision = pending(TransactionType.CREDIT, 1).calculateOutcome(accounts);
		assertThatThrownBy(() -> decision.resultingBalances().put("B", 0L)).isInstanceOf(UnsupportedOperationException.class);
	}

	private Transaction pending(TransactionType type, long amount) {
		return Transaction.pending("tx", type, amount, type == TransactionType.CREDIT ? null : "A",
				type == TransactionType.DEBIT ? null : "B", NOW);
	}
}
