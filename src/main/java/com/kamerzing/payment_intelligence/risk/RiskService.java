package com.kamerzing.payment_intelligence.risk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import com.kamerzing.payment_intelligence.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class RiskService {
	private final RiskRepository repository;
	private final RiskRules rules;
	private final Clock clock;
	private final ObjectMapper json;
	public RiskService(RiskRepository repository, RiskRules rules, Clock clock, ObjectMapper json) {
		this.repository = repository; this.rules = rules; this.clock = clock; this.json = json;
	}
	@Transactional(timeout = 2)
	public RiskResponse evaluate(RiskRequest request) {
		request.validate();
		String command = json.writeValueAsString(request);
		repository.lock(request.attempt_id(), request.source_wallet_id());
		Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		if (request.user_created_at().isAfter(now))
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RISK_CONTEXT_UNAVAILABLE", "Invalid trusted account context.");
		var existing = repository.command(request.attempt_id());
		if (existing.isPresent() && !existing.get().equals(command))
			throw new ApiException(HttpStatus.CONFLICT, "ATTEMPT_CONFLICT", "The attempt has different command parameters.");
		repository.rules(rules.version(), json.writeValueAsString(rules));
		var saved = repository.reusable(request.attempt_id(), now, rules.version());
		if (saved.isPresent()) return json.readValue(saved.get(), RiskResponse.class);
		if (existing.isEmpty()) repository.insert(request, command, now);
		long attempts = repository.attempts(request, now.minus(rules.velocityWindow()), now);
		long recipients = repository.recipients(request, now.minus(rules.recipientWindow()), now);
		Duration age = Duration.between(request.user_created_at(), now);
		var result = rules.evaluate(Long.parseLong(request.amount_minor()), attempts, recipients, age);
		var response = new RiskResponse(request.attempt_id(), UUID.randomUUID(), result.decision(), result.score(), result.rules(),
				rules.version(), hash(command), now, "ALLOW".equals(result.decision()) ? now.plus(rules.allowTtl()) : null);
		repository.save(response, json.writeValueAsString(response), json.writeValueAsString(
				new Facts(now, attempts, recipients, age.toString(), request)));
		return response;
	}
	private static String hash(String command) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(command.getBytes(StandardCharsets.UTF_8))); }
		catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
	}
	public record Facts(Instant server_time, long unique_attempts, long distinct_recipients, String account_age, RiskRequest command) { }
}
