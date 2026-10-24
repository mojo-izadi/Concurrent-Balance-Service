package com.example.concurrentbalanceservice.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "balance.finalization")
public class FinalizationProperties {
	private int maxConcurrentTransactions = 10;
	private int maxRetries = 3;
	private Duration retryDelay = Duration.ofMillis(100);

	public int getMaxConcurrentTransactions() {
		return maxConcurrentTransactions;
	}

	public void setMaxConcurrentTransactions(int maxConcurrentTransactions) {
		this.maxConcurrentTransactions = maxConcurrentTransactions;
	}

	public int getMaxRetries() {
		return maxRetries;
	}

	public void setMaxRetries(int maxRetries) {
		this.maxRetries = maxRetries;
	}

	public Duration getRetryDelay() {
		return retryDelay;
	}

	public void setRetryDelay(Duration retryDelay) {
		this.retryDelay = retryDelay;
	}
}
