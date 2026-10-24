package com.example.concurrentbalanceservice.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.concurrentbalanceservice.service.BalanceService;
import com.example.concurrentbalanceservice.service.exception.AccountNotFoundException;
import com.example.concurrentbalanceservice.service.exception.DuplicateTransactionIdException;
import com.example.concurrentbalanceservice.service.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BalanceController.class)
class BalanceControllerTest {
	@Autowired MockMvc mvc;
	@MockitoBean BalanceService service;

	@ParameterizedTest
	@ValueSource(strings = {"credit", "debit"})
	void acceptsCreditAndDebitRequests(String type) throws Exception {
		mvc.perform(post("/api/accounts/A/" + type).contentType(MediaType.APPLICATION_JSON)
				.content("{\"transactionId\":\"tx\",\"amount\":250}"))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.transactionId").value("tx"))
				.andExpect(jsonPath("$.message").value("Transaction registered successfully"));
		if (type.equals("credit")) verify(service).credit("A", 250, "tx");
		else verify(service).debit("A", 250, "tx");
	}

	@Test
	void acceptsTransfers() throws Exception {
		mvc.perform(post("/api/transfer").contentType(MediaType.APPLICATION_JSON).content("""
				{"transactionId":"tx","sourceAccountId":"A","destinationAccountId":"B","amount":300}
				"""))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.transactionId").value("tx"));
		verify(service).transfer("A", "B", 300, "tx");
	}

	@Test
	void returnsCommittedBalance() throws Exception {
		when(service.getBalance("A")).thenReturn(1_000L);
		mvc.perform(get("/api/accounts/A/balance")).andExpect(status().isOk())
				.andExpect(jsonPath("$.accountId").value("A")).andExpect(jsonPath("$.balance").value(1_000));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "null", "{", "{}", "{\"amount\":null}", "{\"amount\":1.5}",
			"{\"amount\":9223372036854775808}", "{\"amount\":\"invalid\"}"})
	void rejectsMissingMalformedFractionalAndOutOfRangeAmounts(String body) throws Exception {
		mvc.perform(post("/api/accounts/A/credit").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	void transferRequiresAnAmount() throws Exception {
		mvc.perform(post("/api/transfer").contentType(MediaType.APPLICATION_JSON).content("""
				{"transactionId":"tx","sourceAccountId":"A","destinationAccountId":"B"}
				"""))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	void invalidTransactionMapsToBadRequest() throws Exception {
		doThrow(new InvalidRequestException("Amount must be greater than zero")).when(service).debit("A", 0, "tx");
		mvc.perform(post("/api/accounts/A/debit").contentType(MediaType.APPLICATION_JSON)
				.content("{\"transactionId\":\"tx\",\"amount\":0}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Amount must be greater than zero"));
	}

	@Test
	void unknownAccountMapsToNotFound() throws Exception {
		when(service.getBalance("missing")).thenThrow(new AccountNotFoundException("missing"));
		mvc.perform(get("/api/accounts/missing/balance")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Account not found: missing"));
	}

	@Test
	void duplicateTransactionMapsToConflict() throws Exception {
		doThrow(new DuplicateTransactionIdException("tx")).when(service).credit("A", 1, "tx");
		mvc.perform(post("/api/accounts/A/credit").contentType(MediaType.APPLICATION_JSON)
				.content("{\"transactionId\":\"tx\",\"amount\":1}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
	}

	@Test
	void databaseFailureMapsToServiceUnavailableWithoutLeakingDetails() throws Exception {
		when(service.getBalance("A")).thenThrow(new DataAccessResourceFailureException("private connection details"));
		mvc.perform(get("/api/accounts/A/balance")).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message").value("The balance database is temporarily unavailable"));
	}
}
