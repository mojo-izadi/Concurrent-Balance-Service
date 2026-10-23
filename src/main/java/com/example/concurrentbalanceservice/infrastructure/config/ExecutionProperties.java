package com.example.concurrentbalanceservice.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "balance.execution")
public class ExecutionProperties {
	private int maxConcurrentOperations = 10;
	private int maxRetries = 3;
	private Duration retryDelay = Duration.ofMillis(100);

	public int getMaxConcurrentOperations() {
		return maxConcurrentOperations;
	}

	public void setMaxConcurrentOperations(int maxConcurrentOperations) {
		this.maxConcurrentOperations = maxConcurrentOperations;
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
