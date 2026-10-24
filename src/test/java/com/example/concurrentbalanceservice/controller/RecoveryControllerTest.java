package com.example.concurrentbalanceservice.controller;

import static org.mockito.Mockito.*;

import com.example.concurrentbalanceservice.service.TransactionRecoveryService;
import org.junit.jupiter.api.Test;

class RecoveryControllerTest {
	@Test
	void schedulerInvokesTheRecoveryUseCase() {
		var service = mock(TransactionRecoveryService.class);
		new RecoveryController(service).recoverPendingTransactions();
		verify(service).recoverPendingTransactions();
		verifyNoMoreInteractions(service);
	}
}
