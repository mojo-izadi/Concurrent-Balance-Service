package com.example.concurrentbalanceservice.dto;

import java.time.Instant;

public record ApiError(Instant timestamp, int status, String error, String message) {
}
