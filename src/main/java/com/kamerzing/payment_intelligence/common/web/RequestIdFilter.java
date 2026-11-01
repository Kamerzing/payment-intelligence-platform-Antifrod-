package com.kamerzing.payment_intelligence.common.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
	public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		String requestId = (String) request.getAttribute(ATTRIBUTE);
		if (requestId == null) {
			requestId = UUID.randomUUID().toString();
			request.setAttribute(ATTRIBUTE, requestId);
		}
		response.setHeader("X-Request-ID", requestId);
		try (var ignored = MDC.putCloseable("request_id", requestId)) {
			chain.doFilter(request, response);
		}
	}

	@Override
	protected boolean shouldNotFilterErrorDispatch() { return false; }
}
