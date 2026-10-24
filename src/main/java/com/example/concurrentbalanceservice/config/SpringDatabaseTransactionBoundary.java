package com.example.concurrentbalanceservice.config;

import com.example.concurrentbalanceservice.service.DatabaseTransactionBoundary;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class SpringDatabaseTransactionBoundary implements DatabaseTransactionBoundary {
	private final TransactionTemplate databaseTransactionTemplate;

	public SpringDatabaseTransactionBoundary(PlatformTransactionManager databaseTransactionManager) {
		this.databaseTransactionTemplate = new TransactionTemplate(databaseTransactionManager);
		this.databaseTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.databaseTransactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	@Override
	public <T> T inNewDatabaseTransaction(Supplier<T> databaseWork) {
		return databaseTransactionTemplate.execute(databaseTransactionStatus -> databaseWork.get());
	}
}
