package com.kamerzing.payment_intelligence.common.web;

import java.time.Instant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatusCode;

public record ApiError(int status, String code, String message, String request_id, Instant timestamp) {
	public static ApiError of(HttpStatusCode status, HttpServletRequest request) {
		String code = switch (status.value()) {
			case 400 -> "INVALID_REQUEST";
			case 401 -> "UNAUTHORIZED";
			case 403 -> "FORBIDDEN";
			case 404 -> "RESOURCE_NOT_FOUND";
			case 405 -> "METHOD_NOT_ALLOWED";
			case 503 -> "SERVICE_UNAVAILABLE";
			default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_REJECTED";
		};
		String message = status.is5xxServerError() ? "The service could not complete the request."
				: "The request could not be accepted.";
		return new ApiError(status.value(), code, message,
				(String) request.getAttribute(RequestIdFilter.ATTRIBUTE), Instant.now());
	}
}
