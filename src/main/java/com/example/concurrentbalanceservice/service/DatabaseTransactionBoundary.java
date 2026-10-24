package com.example.concurrentbalanceservice.service;

import java.util.function.Supplier;

public interface DatabaseTransactionBoundary {
	<T> T inNewDatabaseTransaction(Supplier<T> work);
}
