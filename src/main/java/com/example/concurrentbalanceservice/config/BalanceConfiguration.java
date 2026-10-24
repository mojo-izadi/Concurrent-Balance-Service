package com.example.concurrentbalanceservice.config;

import com.example.concurrentbalanceservice.service.DatabaseTransactionBoundary;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@EnableScheduling
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
}
