package com.example.concurrentbalanceservice.infrastructure.config;

import com.example.concurrentbalanceservice.application.port.BalanceRepository;
import com.example.concurrentbalanceservice.application.port.JobExecutor;
import com.example.concurrentbalanceservice.application.port.TransactionBoundary;
import com.example.concurrentbalanceservice.application.service.BalanceService;
import com.example.concurrentbalanceservice.application.service.DefaultBalanceService;
import com.example.concurrentbalanceservice.application.service.OperationExecutionService;
import com.example.concurrentbalanceservice.application.service.OperationRecoveryService;
import com.example.concurrentbalanceservice.application.service.TransactionInitializer;
import com.example.concurrentbalanceservice.infrastructure.execution.VirtualThreadJobExecutor;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ExecutionProperties.class)
public class BalanceConfiguration {
	@Bean
	Clock systemClock() {
		return Clock.systemUTC();
	}

	@Bean
	TransactionBoundary transactionBoundary(PlatformTransactionManager transactionManager) {
		return new SpringTransactionBoundary(transactionManager);
	}

	@Bean(destroyMethod = "close")
	ExecutorService balanceVirtualThreadExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}

	@Bean
	OperationExecutionService operationExecutionService(BalanceRepository repository,
			TransactionBoundary transactionBoundary, Clock clock, ExecutionProperties properties) {
		return new OperationExecutionService(repository, transactionBoundary, clock,
				properties.getMaxRetries(), properties.getRetryDelay());
	}

	@Bean
	JobExecutor jobExecutor(ExecutorService balanceVirtualThreadExecutor,
			OperationExecutionService operationExecutionService, ExecutionProperties properties) {
		return new VirtualThreadJobExecutor(balanceVirtualThreadExecutor, operationExecutionService,
				properties.getMaxConcurrentOperations());
	}

	@Bean
	OperationRecoveryService operationRecoveryService(BalanceRepository repository, JobExecutor jobExecutor,
			@Value("${balance.recovery.minimum-pending-age:5s}") Duration minimumPendingAge) {
		return new OperationRecoveryService(repository, jobExecutor, minimumPendingAge);
	}

	@Bean
	TransactionInitializer transactionInitializer(BalanceRepository repository, JobExecutor jobExecutor, Clock clock) {
		return new TransactionInitializer(repository, jobExecutor, clock);
	}

	@Bean
	BalanceService balanceService(TransactionInitializer initializer, BalanceRepository repository) {
		return new DefaultBalanceService(initializer, repository);
	}
}
