package com.example.concurrentbalanceservice.adapters.http;

import java.time.Instant;

public record ApiError(Instant timestamp, int status, String error, String message) {
}
