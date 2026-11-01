package com.kamerzing.payment_intelligence;

import com.kamerzing.payment_intelligence.common.web.ApiErrorWriter;
import com.kamerzing.payment_intelligence.common.web.ApiExceptionHandler;
import com.kamerzing.payment_intelligence.common.web.RequestIdFilter;
import com.kamerzing.payment_intelligence.common.web.StatusController;
import com.kamerzing.payment_intelligence.config.SecurityConfiguration;
import com.kamerzing.payment_intelligence.config.InternalSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StatusController.class)
@Import({SecurityConfiguration.class, InternalSecurityConfiguration.class, ApiErrorWriter.class, ApiExceptionHandler.class, RequestIdFilter.class})
class HttpFoundationTests {
	@Autowired MockMvc mvc;

	@Test
	void reportsOnlyImplementedCapabilitiesAndGeneratesRequestId() throws Exception {
		var result = mvc.perform(get("/api/v1/status").header("X-Request-ID", "client-controlled"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.service").value("payment-intelligence-platform"))
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.capabilities.payment_core").value("DELIVERY_NOT_VERIFIED"))
				.andExpect(jsonPath("$.capabilities.risk").value("RULES_IMPLEMENTED"))
				.andExpect(jsonPath("$.capabilities.ai").value("DISABLED"))
				.andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
				.andReturn();
		String requestId = result.getResponse().getHeader("X-Request-ID");
		assertThat(requestId).isNotEqualTo("client-controlled");
		assertThat(java.util.UUID.fromString(requestId)).isNotNull();
	}

	@Test
	void keepsBusinessAndInternalApisClosedWithoutCreatingADemoLogin() throws Exception {
		mvc.perform(get("/api/v1/investigations"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		mvc.perform(post("/internal/v1/risk/evaluations").with(csrf()))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/investigations").with(user("forged-admin").roles("ADMIN")))
				.andExpect(status().isForbidden());
		mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
	}

	@Test
	void keepsCsrfProtectionAndUsesTheSameErrorContract() throws Exception {
		var result = mvc.perform(post("/api/v1/investigations"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.status").value(403))
				.andExpect(jsonPath("$.code").value("FORBIDDEN"))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.timestamp").isString()).andReturn();
		mvc.perform(get("/assets/missing.js"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		assertThat(result.getResponse().getContentAsString())
				.contains(result.getResponse().getHeader("X-Request-ID"))
				.doesNotContain("stackTrace", "password", "org.springframework");
	}
}
