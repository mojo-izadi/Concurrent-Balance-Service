package com.example.concurrentbalanceservice.controller;

import com.example.concurrentbalanceservice.dto.BalanceResponse;
import com.example.concurrentbalanceservice.dto.TransactionAcceptedResponse;
import com.example.concurrentbalanceservice.dto.TransactionRequest;
import com.example.concurrentbalanceservice.dto.TransferRequest;
import com.example.concurrentbalanceservice.service.BalanceService;
import com.example.concurrentbalanceservice.service.exception.InvalidRequestException;
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
	public ResponseEntity<TransactionAcceptedResponse> credit(@PathVariable String accountId,
			@RequestBody TransactionRequest request) {
		long amount = requireAmount(request.amount());
		balanceService.credit(accountId, amount, request.transactionId());
		return ResponseEntity.accepted().body(new TransactionAcceptedResponse(
				request.transactionId(), "Transaction registered successfully"));
	}

	@PostMapping("/accounts/{accountId}/debit")
	public ResponseEntity<TransactionAcceptedResponse> debit(@PathVariable String accountId,
			@RequestBody TransactionRequest request) {
		long amount = requireAmount(request.amount());
		balanceService.debit(accountId, amount, request.transactionId());
		return ResponseEntity.accepted().body(new TransactionAcceptedResponse(
				request.transactionId(), "Transaction registered successfully"));
	}

	@PostMapping("/transfer")
	public ResponseEntity<TransactionAcceptedResponse> transfer(@RequestBody TransferRequest request) {
		long amount = requireAmount(request.amount());
		balanceService.transfer(request.sourceAccountId(), request.destinationAccountId(), amount,
				request.transactionId());
		return ResponseEntity.accepted().body(new TransactionAcceptedResponse(
				request.transactionId(), "Transaction registered successfully"));
	}

	@GetMapping("/accounts/{accountId}/balance")
	public BalanceResponse getBalance(@PathVariable String accountId) {
		return new BalanceResponse(accountId, balanceService.getBalance(accountId));
	}

	private static long requireAmount(Long amount) {
		if (amount == null) {
			throw new InvalidRequestException("Amount is required");
		}
		return amount;
	}
}
