package com.kamerzing.payment_intelligence.config;

import com.kamerzing.payment_intelligence.common.web.ApiErrorWriter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ApiErrorWriter errors, Environment environment) throws Exception {
		boolean localDocumentation = environment.acceptsProfiles(Profiles.of("local-lite"))
				&& !environment.acceptsProfiles(Profiles.of("server"));
		http.authorizeHttpRequests(authorize -> {
			authorize.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
			authorize.requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/intelligence/assets/**", "/favicon.svg",
					"/api/v1/status", "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll();
			if (localDocumentation) authorize.requestMatchers(HttpMethod.GET,
					"/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll();
			// No business API or authentication bypass exists in this foundation stage.
			authorize.anyRequest().denyAll();
		})
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
					"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'")))
			.exceptionHandling(exceptions -> exceptions
					.authenticationEntryPoint((request, response, exception) -> errors.write(request, response, HttpStatus.UNAUTHORIZED))
					.accessDeniedHandler((request, response, exception) -> errors.write(request, response, HttpStatus.FORBIDDEN)));
		// Keep CSRF protection enabled. Browser authentication will stay in Payment Core.
		return http.build();
	}

	@Bean
	UserDetailsService noLocalAccounts() {
		// Prevent a generated Spring Security password from becoming a second login system.
		return username -> { throw new UsernameNotFoundException("No local accounts are configured."); };
	}
}
