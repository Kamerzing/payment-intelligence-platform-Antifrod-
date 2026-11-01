package com.kamerzing.payment_intelligence.common.web;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApiErrorWriter {
	private final ObjectMapper json;
	public ApiErrorWriter(ObjectMapper json) { this.json = json; }

	public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		json.writeValue(response.getOutputStream(), ApiError.of(status, request));
	}
}
