package com.example.concurrentbalanceservice.adapters.controller;

import com.example.concurrentbalanceservice.application.service.OperationRecoveryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RecoveryController {
	private final OperationRecoveryService recoveryService;

	public RecoveryController(OperationRecoveryService recoveryService) {
		this.recoveryService = recoveryService;
	}

	@Scheduled(fixedDelayString = "${balance.recovery.fixed-delay:5s}",
			initialDelayString = "${balance.recovery.fixed-delay:5s}")
	public void recoverPendingOperations() {
		recoveryService.recoverPendingOperations();
	}
}
