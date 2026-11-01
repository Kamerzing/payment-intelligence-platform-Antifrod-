package com.kamerzing.payment_intelligence.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
	private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiError> apiFailure(ApiException exception, HttpServletRequest request) {
		return ResponseEntity.status(exception.status()).body(new ApiError(exception.status().value(), exception.code(),
				exception.getMessage(), (String) request.getAttribute(RequestIdFilter.ATTRIBUTE), java.time.Instant.now()));
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return super.handleExceptionInternal(exception,
				ApiError.of(status, ((ServletWebRequest) request).getRequest()), headers, status, request);
	}

	@ExceptionHandler({DataAccessException.class, org.springframework.transaction.TransactionException.class})
	public ResponseEntity<ApiError> databaseFailure(RuntimeException exception, HttpServletRequest request) {
		LOG.error("Database request failure: type={}", exception.getClass().getName());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(ApiError.of(HttpStatus.SERVICE_UNAVAILABLE, request));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> unexpectedFailure(Exception exception, HttpServletRequest request) {
		LOG.error("Unexpected request failure: type={}", exception.getClass().getName());
		return ResponseEntity.internalServerError().body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR, request));
	}
}
