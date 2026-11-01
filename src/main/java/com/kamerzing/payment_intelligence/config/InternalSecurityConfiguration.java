package com.kamerzing.payment_intelligence.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import com.kamerzing.payment_intelligence.common.web.ApiErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class InternalSecurityConfiguration {
	@Bean
	@Order(1)
	SecurityFilterChain internalChain(HttpSecurity http, ApiErrorWriter errors,
			@Value("${pip.integration.core-token:}") String token,
			@Value("${pip.integration.analyst-token:}") String analystToken) throws Exception {
		if(token.length()>=32 && token.equals(analystToken)) throw new IllegalArgumentException("Analyst and risk credentials must be separate.");
		// This chain never reads browser cookies. Only these machine routes omit CSRF.
		http.securityMatcher("/internal/**")
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.securityContext(context -> context.requireExplicitSave(true))
			.formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
			.addFilterBefore(new OncePerRequestFilter() {
				@Override
				protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
						throws ServletException, IOException {
					var headers = request.getHeaders("Authorization");
					String supplied = headers.hasMoreElements() ? headers.nextElement() : "";
					boolean analyst=request.getRequestURI().startsWith(request.getContextPath()+"/internal/v1/analyst/");
					String expected=analyst?analystToken:token;
					if (expected.length() < 32 || headers.hasMoreElements() || !MessageDigest.isEqual(
							("Bearer " + expected).getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
						errors.write(request, response, HttpStatus.UNAUTHORIZED);
						return;
					}
					if(analyst) {
						try {
							var ids=java.util.Collections.list(request.getHeaders("X-Analyst-Id"));
							if(ids.size()!=1 || !java.util.UUID.fromString(ids.getFirst()).toString().equals(ids.getFirst())) throw new IllegalArgumentException();
						} catch(IllegalArgumentException invalid) { errors.write(request,response,HttpStatus.UNAUTHORIZED); return; }
					}
					var context = SecurityContextHolder.createEmptyContext();
					context.setAuthentication(new UsernamePasswordAuthenticationToken("payment-core", null,
							List.of(new SimpleGrantedAuthority(analyst?"ROLE_ANALYST":"ROLE_CORE"))));
					SecurityContextHolder.setContext(context);
					try { chain.doFilter(request, response); } finally { SecurityContextHolder.clearContext(); }
				}
			}, AnonymousAuthenticationFilter.class)
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(org.springframework.http.HttpMethod.GET, "/internal/v1/analyst/**").hasRole("ANALYST")
				.requestMatchers(org.springframework.http.HttpMethod.POST, "/internal/v1/analyst/investigations/*").hasRole("ANALYST")
				.requestMatchers(org.springframework.http.HttpMethod.POST, "/internal/v1/analyst/attempts/*/ai-analyses").hasRole("ANALYST")
				.requestMatchers(org.springframework.http.HttpMethod.POST, "/internal/v1/events", "/internal/v1/risk/evaluations").hasRole("CORE")
				.anyRequest().denyAll())
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint((req, res, ex) -> errors.write(req, res, HttpStatus.UNAUTHORIZED))
				.accessDeniedHandler((req, res, ex) -> errors.write(req, res, HttpStatus.FORBIDDEN)));
		return http.build();
	}
}
