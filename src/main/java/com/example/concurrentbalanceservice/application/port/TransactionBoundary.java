package com.example.concurrentbalanceservice.application.port;

import java.util.function.Supplier;

public interface TransactionBoundary {
	<T> T inNewTransaction(Supplier<T> work);
}
