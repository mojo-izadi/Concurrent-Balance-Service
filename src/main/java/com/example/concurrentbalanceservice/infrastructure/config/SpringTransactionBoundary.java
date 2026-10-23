package com.example.concurrentbalanceservice.infrastructure.config;

import com.example.concurrentbalanceservice.application.port.TransactionBoundary;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class SpringTransactionBoundary implements TransactionBoundary {
	private final TransactionTemplate transactionTemplate;

	public SpringTransactionBoundary(PlatformTransactionManager transactionManager) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	@Override
	public <T> T inNewTransaction(Supplier<T> work) {
		return transactionTemplate.execute(status -> work.get());
	}
}
