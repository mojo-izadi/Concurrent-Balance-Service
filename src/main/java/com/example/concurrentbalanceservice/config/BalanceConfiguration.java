package com.example.concurrentbalanceservice.config;

import com.example.concurrentbalanceservice.execution.JobExecutor;
import com.example.concurrentbalanceservice.execution.VirtualThreadJobExecutor;
import com.example.concurrentbalanceservice.repository.BalanceRepository;
import com.example.concurrentbalanceservice.service.BalanceService;
import com.example.concurrentbalanceservice.service.BalanceServiceImpl;
import com.example.concurrentbalanceservice.service.TransactionFinalizationService;
import com.example.concurrentbalanceservice.service.TransactionFinalizationServiceImpl;
import com.example.concurrentbalanceservice.service.TransactionRecoveryService;
import com.example.concurrentbalanceservice.service.TransactionRecoveryServiceImpl;
import com.example.concurrentbalanceservice.service.DatabaseTransactionBoundary;
import com.example.concurrentbalanceservice.service.TransactionInitializer;
import com.example.concurrentbalanceservice.service.TransactionInitializerImpl;
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
@EnableConfigurationProperties(FinalizationProperties.class)
public class BalanceConfiguration {
	@Bean
	Clock systemClock() {
		return Clock.systemUTC();
	}

	@Bean
	DatabaseTransactionBoundary databaseTransactionBoundary(PlatformTransactionManager databaseTransactionManager) {
		return new SpringDatabaseTransactionBoundary(databaseTransactionManager);
	}

	@Bean(destroyMethod = "close")
	ExecutorService balanceVirtualThreadExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}

	@Bean
	TransactionFinalizationService transactionFinalizationService(BalanceRepository repository,
			DatabaseTransactionBoundary databaseTransactionBoundary, Clock clock, FinalizationProperties properties) {
		return new TransactionFinalizationServiceImpl(repository, databaseTransactionBoundary, clock,
				properties.getMaxRetries(), properties.getRetryDelay());
	}

	@Bean
	JobExecutor jobExecutor(ExecutorService balanceVirtualThreadExecutor,
			TransactionFinalizationService transactionFinalizationService, FinalizationProperties properties) {
		return new VirtualThreadJobExecutor(balanceVirtualThreadExecutor, transactionFinalizationService,
				properties.getMaxConcurrentTransactions());
	}

	@Bean
	TransactionRecoveryService transactionRecoveryService(BalanceRepository repository, JobExecutor jobExecutor,
			@Value("${balance.recovery.minimum-pending-age:5s}") Duration minimumPendingAge) {
		return new TransactionRecoveryServiceImpl(repository, jobExecutor, minimumPendingAge);
	}

	@Bean
	TransactionInitializer transactionInitializer(BalanceRepository repository, JobExecutor jobExecutor, Clock clock) {
		return new TransactionInitializerImpl(repository, jobExecutor, clock);
	}

	@Bean
	BalanceService balanceService(TransactionInitializer initializer, BalanceRepository repository) {
		return new BalanceServiceImpl(initializer, repository);
	}
}
