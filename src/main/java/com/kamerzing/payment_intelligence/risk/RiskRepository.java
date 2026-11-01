package com.kamerzing.payment_intelligence.risk;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.kamerzing.payment_intelligence.common.web.ApiException;
import org.springframework.http.HttpStatus;

@Repository
public class RiskRepository {
	private final JdbcTemplate jdbc;
	public RiskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
	public void lock(UUID attempt, UUID wallet) {
		// The attempt lock also protects conflicting retries that name different wallets.
		jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))", rs -> { }, attempt.toString());
		jdbc.update("INSERT INTO risk_wallet_lock(wallet_id) VALUES (?) ON CONFLICT DO NOTHING", wallet);
		jdbc.queryForObject("SELECT wallet_id FROM risk_wallet_lock WHERE wallet_id=? FOR UPDATE", UUID.class, wallet);
	}
	public Optional<String> command(UUID attempt) {
		return jdbc.query("SELECT command FROM risk_attempt WHERE attempt_id=?", (rs, row) -> rs.getString(1), attempt).stream().findFirst();
	}
	public void insert(RiskRequest request, String command, Instant now) {
		jdbc.update("INSERT INTO risk_attempt VALUES (?, ?, ?, ?, ?, ?)", request.attempt_id(), request.source_wallet_id(),
				request.target_wallet_id(), request.command_type().name(), command, Timestamp.from(now));
	}
	public Optional<String> reusable(UUID attempt, Instant now, String version) {
		return jdbc.query("""
			SELECT response FROM risk_evaluation WHERE attempt_id=? AND (decision='DENY' OR (expires_at>? AND rule_set_version=?))
			ORDER BY (decision='DENY') DESC, evaluated_at DESC, decision_id DESC LIMIT 1
			""", (rs, row) -> rs.getString(1), attempt, Timestamp.from(now), version).stream().findFirst();
	}
	public long attempts(RiskRequest request, Instant lower, Instant now) {
		return jdbc.queryForObject("""
			SELECT count(*) FROM risk_attempt WHERE wallet_id=? AND
			((first_seen_at>? AND first_seen_at<=?) OR attempt_id=?)
			""", Long.class, request.source_wallet_id(), Timestamp.from(lower), Timestamp.from(now), request.attempt_id());
	}
	public long recipients(RiskRequest request, Instant lower, Instant now) {
		return jdbc.queryForObject("""
			SELECT count(DISTINCT target_wallet_id) FROM risk_attempt WHERE wallet_id=? AND
			((first_seen_at>? AND first_seen_at<=?) OR attempt_id=?)
			""", Long.class, request.source_wallet_id(), Timestamp.from(lower), Timestamp.from(now), request.attempt_id());
	}
	public void rules(String version, String configuration) {
		jdbc.update("INSERT INTO risk_rule_set(version, configuration) VALUES (?, ?) ON CONFLICT DO NOTHING", version, configuration);
		if (!configuration.equals(jdbc.queryForObject("SELECT configuration FROM risk_rule_set WHERE version=?", String.class, version)))
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RISK_CONFIGURATION_CONFLICT", "Rule version must change with its configuration.");
	}
	public void save(RiskResponse response, String encoded, String facts) {
		jdbc.update("INSERT INTO risk_evaluation VALUES (?, ?, ?, ?, ?, ?, ?, ?)", response.decision_id(), response.attempt_id(),
				response.rule_set_version(), response.decision(), Timestamp.from(response.evaluated_at()),
				response.expires_at() == null ? null : Timestamp.from(response.expires_at()), encoded, facts);
	}
}
