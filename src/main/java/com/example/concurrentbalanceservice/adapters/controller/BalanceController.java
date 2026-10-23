package com.example.concurrentbalanceservice.adapters.controller;

import com.example.concurrentbalanceservice.adapters.http.BalanceResponse;
import com.example.concurrentbalanceservice.adapters.http.OperationAcceptedResponse;
import com.example.concurrentbalanceservice.adapters.http.OperationRequest;
import com.example.concurrentbalanceservice.adapters.http.TransferRequest;
import com.example.concurrentbalanceservice.application.exception.InvalidOperationException;
import com.example.concurrentbalanceservice.application.service.BalanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BalanceController {
	private final BalanceService balanceService;

	public BalanceController(BalanceService balanceService) {
		this.balanceService = balanceService;
	}

	@PostMapping("/accounts/{accountId}/credit")
	public ResponseEntity<OperationAcceptedResponse> credit(@PathVariable String accountId,
			@RequestBody OperationRequest request) {
		long amount = requireAmount(request.amount());
		balanceService.credit(accountId, amount, request.transactionId());
		return ResponseEntity.accepted().body(new OperationAcceptedResponse(
				request.transactionId(), "Transaction registered successfully"));
	}

	@PostMapping("/accounts/{accountId}/debit")
	public ResponseEntity<OperationAcceptedResponse> debit(@PathVariable String accountId,
			@RequestBody OperationRequest request) {
		long amount = requireAmount(request.amount());
		balanceService.debit(accountId, amount, request.transactionId());
		return ResponseEntity.accepted().body(new OperationAcceptedResponse(
				request.transactionId(), "Transaction registered successfully"));
	}

	@PostMapping("/transfers")
	public ResponseEntity<OperationAcceptedResponse> transfer(@RequestBody TransferRequest request) {
		long amount = requireAmount(request.amount());
		balanceService.transfer(request.sourceAccountId(), request.destinationAccountId(), amount,
				request.transactionId());
		return ResponseEntity.accepted().body(new OperationAcceptedResponse(
				request.transactionId(), "Transaction registered successfully"));
	}

	@GetMapping("/accounts/{accountId}/balance")
	public BalanceResponse getBalance(@PathVariable String accountId) {
		return new BalanceResponse(accountId, balanceService.getBalance(accountId));
	}

	private static long requireAmount(Long amount) {
		if (amount == null) {
			throw new InvalidOperationException("Amount is required");
		}
		return amount;
	}
}
