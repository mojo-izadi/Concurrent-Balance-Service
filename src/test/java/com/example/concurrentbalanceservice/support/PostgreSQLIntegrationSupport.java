package com.example.concurrentbalanceservice.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.concurrentbalanceservice.adapters.persistence.entity.TransactionJpaEntity;
import com.example.concurrentbalanceservice.adapters.persistence.mapper.TransactionMapper;
import com.example.concurrentbalanceservice.application.port.BalanceRepository;
import com.example.concurrentbalanceservice.application.port.TransactionBoundary;
import com.example.concurrentbalanceservice.application.service.OperationExecutionService;
import com.example.concurrentbalanceservice.domain.Transaction;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PostgreSQLIntegrationSupport {
	// One isolated database for the test JVM; Testcontainers removes it on JVM exit.
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");
	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("balance.recovery.fixed-delay", () -> "1h");
		registry.add("balance.recovery.minimum-pending-age", () -> "1h");
	}

	@Autowired protected BalanceRepository repository;
	@Autowired protected TransactionBoundary boundary;
	@Autowired protected OperationExecutionService executionService;
	@Autowired protected EntityManager entityManager;

	protected String newAccount(long balance) {
		String id = newId();
		boundary.inNewTransaction(() -> entityManager.createNativeQuery("INSERT INTO accounts (account_id, balance) VALUES (:id, :balance)")
				.setParameter("id", id).setParameter("balance", balance).executeUpdate());
		return id;
	}

	protected Transaction storedTransaction(String id) {
		return boundary.inNewTransaction(() -> new TransactionMapper().toDomain(entityManager.find(TransactionJpaEntity.class, id)));
	}

	protected long operationCount(String id) {
		return boundary.inNewTransaction(() -> ((Number) entityManager.createNativeQuery(
				"SELECT COUNT(*) FROM balance_operations WHERE transaction_id = :id").setParameter("id", id).getSingleResult()).longValue());
	}

	protected static String newId() {
		return UUID.randomUUID().toString();
	}

	protected static void runConcurrently(int count, IntConsumer action) throws Exception {
		var ready = new CountDownLatch(count);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var futures = new ArrayList<Future<?>>();
			for (int i = 0; i < count; i++) {
				int index = i;
				futures.add(executor.submit(() -> {
					ready.countDown();
					awaitLatch(start);
					action.accept(index);
				}));
			}
			try {
				assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			}
			finally {
				start.countDown();
			}
			for (var future : futures) future.get(45, TimeUnit.SECONDS);
		}
	}

	protected static void awaitLatch(CountDownLatch latch) {
		try {
			assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}
}
