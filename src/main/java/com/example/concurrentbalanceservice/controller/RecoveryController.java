package com.example.concurrentbalanceservice.controller;

import com.example.concurrentbalanceservice.service.TransactionRecoveryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RecoveryController {
	private final TransactionRecoveryService recoveryService;

	public RecoveryController(TransactionRecoveryService recoveryService) {
		this.recoveryService = recoveryService;
	}

	@Scheduled(fixedDelayString = "${balance.recovery.fixed-delay:5s}",
			initialDelayString = "${balance.recovery.fixed-delay:5s}")
	public void recoverPendingTransactions() {
		recoveryService.recoverPendingTransactions();
	}
}
