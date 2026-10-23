package com.example.concurrentbalanceservice.adapters.controller;

import static org.mockito.Mockito.*;

import com.example.concurrentbalanceservice.application.service.OperationRecoveryService;
import org.junit.jupiter.api.Test;

class RecoveryControllerTest {
	@Test
	void schedulerInvokesTheRecoveryUseCase() {
		var service = mock(OperationRecoveryService.class);
		new RecoveryController(service).recoverPendingOperations();
		verify(service).recoverPendingOperations();
		verifyNoMoreInteractions(service);
	}
}
