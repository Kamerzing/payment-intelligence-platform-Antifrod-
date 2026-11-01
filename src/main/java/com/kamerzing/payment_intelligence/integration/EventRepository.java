package com.kamerzing.payment_intelligence.integration;

import com.kamerzing.payment_intelligence.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {
	private final JdbcTemplate jdbc;
	public EventRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
	public boolean append(CoreEvent event, String payload) {
		if(event.operation()==null && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM risk_evaluation e JOIN risk_attempt a ON a.attempt_id=e.attempt_id WHERE e.decision_id=? AND e.attempt_id=? AND e.decision='DENY' AND a.command::jsonb=(?::jsonb->'rejected_command'))",Boolean.class,event.decision_id(),event.attempt_id(),payload)))
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"RISK_HISTORY_UNAVAILABLE","The original risk decision is unavailable.");
		int inserted = jdbc.update("""
			INSERT INTO integration_event(source_id,event_id,source_position,operation_id,source_version,payload,attempt_id)
			VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
			""", event.source_id(), event.event_id(), Long.parseLong(event.source_position()),
				event.operation()==null?null:event.operation().operation_id(), Long.parseLong(event.source_version()), payload,event.attempt_id());
		if (inserted == 0) {
			var previous = jdbc.query("SELECT payload FROM integration_event WHERE source_id=? AND event_id=?",
					(rs, row) -> rs.getString(1), event.source_id(), event.event_id());
			if (previous.size() != 1 || !previous.getFirst().equals(payload))
				throw new ApiException(HttpStatus.CONFLICT, "EVENT_CONFLICT", "Event identity or version has different data.");
		}
		if(event.operation()==null) return inserted==1;
		jdbc.update("""
			INSERT INTO operation_view(source_id,operation_id,source_version,payload) VALUES (?,?,?,?)
			ON CONFLICT(source_id,operation_id) DO UPDATE SET source_version=EXCLUDED.source_version,payload=EXCLUDED.payload
			WHERE operation_view.source_version<EXCLUDED.source_version
			""", event.source_id(), event.operation().operation_id(), Long.parseLong(event.source_version()), payload);
		return inserted == 1;
	}
	public void rebuild(String source) {
		// A transaction-level source lock is also held by ingestion and import.
		jdbc.update("DELETE FROM operation_view WHERE source_id=?", source);
		jdbc.update("""
			INSERT INTO operation_view SELECT DISTINCT ON (source_id,operation_id) source_id,operation_id,source_version,payload
			FROM integration_event WHERE source_id=? AND operation_id IS NOT NULL ORDER BY source_id,operation_id,source_version DESC
			""", source);
	}
	public void sourceLock(String source, boolean exclusive) {
		jdbc.query("SELECT " + (exclusive ? "pg_advisory_xact_lock" : "pg_advisory_xact_lock_shared")
				+ "(hashtextextended(?::text, 1))", rs -> { }, source);
	}
}
