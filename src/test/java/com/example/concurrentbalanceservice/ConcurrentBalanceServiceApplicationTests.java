package com.example.concurrentbalanceservice;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.example.concurrentbalanceservice.domain.TransactionStatus;
import com.example.concurrentbalanceservice.execution.JobExecutor;
import com.example.concurrentbalanceservice.service.TransactionRecoveryServiceImpl;
import com.example.concurrentbalanceservice.service.TransactionInitializerImpl;
import com.example.concurrentbalanceservice.support.PostgreSQLIntegrationSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

class ConcurrentBalanceServiceApplicationTests extends PostgreSQLIntegrationSupport {
	@LocalServerPort int port;
	@Autowired ObjectMapper json;
	@Autowired JobExecutor executor;
	private final HttpClient client = HttpClient.newHttpClient();

	@Test
	void realHttpRequestsExecuteAsynchronouslyAndReadCommittedBalances() throws Exception {
		String a = newAccount(1_000);
		String b = newAccount(500);
		String credit = newId();
		assertThat(post("/api/accounts/" + a + "/credit", transaction(credit, 500)).statusCode()).isEqualTo(202);
		assertFinalized(credit);
		assertThat(repository.getBalance(a)).isEqualTo(1_500);
		String debit = newId();
		assertThat(post("/api/accounts/" + a + "/debit", transaction(debit, 700)).statusCode()).isEqualTo(202);
		assertFinalized(debit);
		String transfer = newId();
		assertThat(post("/api/transfers", transfer(transfer, a, b, 300)).statusCode()).isEqualTo(202);
		assertFinalized(transfer);
		var response = get("/api/accounts/" + a + "/balance");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(json.readTree(response.body()).path("balance").asLong()).isEqualTo(500);
		assertThat(repository.getBalance(b)).isEqualTo(800);
		assertThat(get("/v3/api-docs").statusCode()).isEqualTo(200);
	}

	@Test
	void duplicateHttpRequestsReturnConflictAndApplyExactlyOnce() throws Exception {
		String account = newAccount(1_000);
		String id = newId();
		String path = "/api/accounts/" + account + "/credit";
		assertThat(post(path, transaction(id, 100)).statusCode()).isEqualTo(202);
		assertFinalized(id);
		assertThat(post(path, transaction(id, 100)).statusCode()).isEqualTo(409);
		assertThat(post(path, transaction(id, 500)).statusCode()).isEqualTo(409);
		assertThat(repository.getBalance(account)).isEqualTo(1_100);
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1})
	void invalidAmountsAreRejectedForEveryTransactionWithoutCreatingWork(long amount) throws Exception {
		String a = newAccount(1_000);
		String b = newAccount(500);
		for (String type : List.of("credit", "debit", "transfer")) {
			String id = newId();
			String path = type.equals("transfer") ? "/api/transfers" : "/api/accounts/" + a + "/" + type;
			String body = type.equals("transfer") ? transfer(id, a, b, amount) : transaction(id, amount);
			assertThat(post(path, body).statusCode()).isEqualTo(400);
			assertThat(transactionCount(id)).isZero();
		}
		assertThat(repository.getBalance(a)).isEqualTo(1_000);
		assertThat(repository.getBalance(b)).isEqualTo(500);
	}

	@Test
	void unknownAccountsAndSameAccountTransfersAreRejected() throws Exception {
		String a = newAccount(1_000);
		String missing = newId();
		assertThat(get("/api/accounts/" + missing + "/balance").statusCode()).isEqualTo(404);
		for (String type : List.of("credit", "debit")) {
			String id = newId();
			assertThat(post("/api/accounts/" + missing + "/" + type, transaction(id, 100)).statusCode()).isEqualTo(404);
			assertThat(transactionCount(id)).isZero();
		}
		for (String[] accounts : List.of(new String[]{a, missing}, new String[]{missing, a}, new String[]{a, a})) {
			String id = newId();
			assertThat(post("/api/transfers", transfer(id, accounts[0], accounts[1], 100)).statusCode())
					.isEqualTo(accounts[0].equals(accounts[1]) ? 400 : 404);
			assertThat(transactionCount(id)).isZero();
		}
	}

	@Test
	void insufficientFundsAreAcceptedThenFailWithoutAffectingBalances() throws Exception {
		String a = newAccount(1_000);
		String b = newAccount(500);
		String id = newId();
		assertThat(post("/api/transfers", transfer(id, a, b, 1_001)).statusCode()).isEqualTo(202);
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
				assertThat(storedTransaction(id).status()).isEqualTo(TransactionStatus.FAILED));
		assertThat(storedTransaction(id).failureReason()).isEqualTo("INSUFFICIENT_BALANCE");
		assertThat(repository.getBalance(a)).isEqualTo(1_000);
		assertThat(repository.getBalance(b)).isEqualTo(500);
	}

	@Test
	void recoveryCompletesCommittedWorkAfterInitialDispatchFailure() {
		String account = newAccount(1_000);
		String id = newId();
		var rejected = new TransactionInitializerImpl(repository, ignored -> { throw new RejectedExecutionException(); }, Clock.systemUTC());
		rejected.credit(account, 100, id);
		assertThat(storedTransaction(id).status()).isEqualTo(TransactionStatus.PENDING);
		assertThat(repository.getBalance(account)).isEqualTo(1_000);
		var recovery = new TransactionRecoveryServiceImpl(repository, executor, Duration.ZERO);
		recovery.recoverPendingTransactions();
		assertFinalized(id);
		recovery.recoverPendingTransactions();
		assertThat(repository.getBalance(account)).isEqualTo(1_100);
		assertThat(transactionCount(id)).isEqualTo(1);
	}

	private void assertFinalized(String id) {
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
				assertThat(storedTransaction(id).status()).isEqualTo(TransactionStatus.FINALIZED));
	}

	private String transaction(String id, long amount) {
		return "{\"transactionId\":\"" + id + "\",\"amount\":" + amount + "}";
	}

	private String transfer(String id, String source, String destination, long amount) {
		return "{\"transactionId\":\"" + id + "\",\"sourceAccountId\":\"" + source
				+ "\",\"destinationAccountId\":\"" + destination + "\",\"amount\":" + amount + "}";
	}

	private HttpResponse<String> post(String path, String body) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}
}
