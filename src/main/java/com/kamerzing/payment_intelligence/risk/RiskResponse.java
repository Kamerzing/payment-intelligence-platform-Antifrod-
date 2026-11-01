package com.kamerzing.payment_intelligence.risk;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RiskResponse(UUID attempt_id, UUID decision_id, String decision, int score,
		List<String> triggered_rules, String rule_set_version, String command_hash,
		Instant evaluated_at, Instant expires_at) { }
