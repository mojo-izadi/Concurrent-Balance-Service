package com.example.concurrentbalanceservice.controller;

import com.example.concurrentbalanceservice.dto.ApiError;
import com.example.concurrentbalanceservice.service.exception.AccountNotFoundException;
import com.example.concurrentbalanceservice.service.exception.DuplicateTransactionIdException;
import com.example.concurrentbalanceservice.service.exception.InvalidRequestException;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
	@ExceptionHandler(InvalidRequestException.class)
	public ResponseEntity<ApiError> invalidTransaction(InvalidRequestException exception) {
		return error(HttpStatus.BAD_REQUEST, exception.getMessage());
	}

	@ExceptionHandler(AccountNotFoundException.class)
	public ResponseEntity<ApiError> accountNotFound(AccountNotFoundException exception) {
		return error(HttpStatus.NOT_FOUND, exception.getMessage());
	}

	@ExceptionHandler(DuplicateTransactionIdException.class)
	public ResponseEntity<ApiError> duplicateTransaction(DuplicateTransactionIdException exception) {
		return error(HttpStatus.CONFLICT, exception.getMessage());
	}

	@ExceptionHandler(DataAccessException.class)
	public ResponseEntity<ApiError> databaseUnavailable(DataAccessException exception) {
		return error(HttpStatus.SERVICE_UNAVAILABLE, "The balance database is temporarily unavailable");
	}

	private static ResponseEntity<ApiError> error(HttpStatus status, String message) {
		return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(),
				status.getReasonPhrase(), message));
	}
}
