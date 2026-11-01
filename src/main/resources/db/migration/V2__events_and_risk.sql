CREATE TABLE integration_event (
	source_id VARCHAR(64) NOT NULL,
	event_id UUID NOT NULL,
	source_position BIGINT NOT NULL CHECK (source_position > 0),
	operation_id UUID NOT NULL,
	source_version BIGINT NOT NULL CHECK (source_version > 0),
	payload TEXT NOT NULL,
	received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
	PRIMARY KEY (source_id, event_id),
	UNIQUE (source_id, source_position),
	UNIQUE (source_id, operation_id, source_version)
);
CREATE TABLE operation_view (
	source_id VARCHAR(64) NOT NULL,
	operation_id UUID NOT NULL,
	source_version BIGINT NOT NULL,
	payload TEXT NOT NULL,
	PRIMARY KEY (source_id, operation_id)
);
CREATE TABLE history_import (
	source_id VARCHAR(64) NOT NULL,
	export_id UUID NOT NULL,
	cursor BIGINT NOT NULL CHECK (cursor >= 0),
	after_operation_id UUID,
	complete BOOLEAN NOT NULL DEFAULT FALSE,
	PRIMARY KEY (source_id, export_id)
);

CREATE TABLE risk_wallet_lock (wallet_id UUID PRIMARY KEY);
CREATE TABLE risk_rule_set (
	version VARCHAR(64) PRIMARY KEY,
	configuration TEXT NOT NULL,
	created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE risk_attempt (
	attempt_id UUID PRIMARY KEY,
	wallet_id UUID NOT NULL REFERENCES risk_wallet_lock(wallet_id),
	target_wallet_id UUID,
	command_type VARCHAR(32) NOT NULL CHECK (command_type IN ('TRANSFER','WITHDRAWAL','CAPTURE_TRANSFER','CAPTURE_WITHDRAWAL')),
	command TEXT NOT NULL,
	first_seen_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX risk_attempt_window ON risk_attempt(wallet_id, first_seen_at);
CREATE TABLE risk_evaluation (
	decision_id UUID PRIMARY KEY,
	attempt_id UUID NOT NULL REFERENCES risk_attempt(attempt_id),
	rule_set_version VARCHAR(64) NOT NULL REFERENCES risk_rule_set(version),
	decision VARCHAR(5) NOT NULL CHECK (decision IN ('ALLOW','DENY')),
	evaluated_at TIMESTAMPTZ NOT NULL,
	expires_at TIMESTAMPTZ,
	response TEXT NOT NULL,
	facts TEXT NOT NULL,
	CHECK ((decision = 'ALLOW') = (expires_at IS NOT NULL))
);
CREATE INDEX risk_evaluation_attempt ON risk_evaluation(attempt_id, evaluated_at DESC);
CREATE UNIQUE INDEX risk_evaluation_final_deny ON risk_evaluation(attempt_id) WHERE decision = 'DENY';

CREATE FUNCTION protect_risk_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	RAISE EXCEPTION 'Risk history is immutable' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER immutable_risk_attempt BEFORE UPDATE OR DELETE ON risk_attempt FOR EACH ROW EXECUTE FUNCTION protect_risk_history();
CREATE TRIGGER immutable_risk_evaluation BEFORE UPDATE OR DELETE ON risk_evaluation FOR EACH ROW EXECUTE FUNCTION protect_risk_history();
CREATE TRIGGER immutable_risk_rules BEFORE UPDATE OR DELETE ON risk_rule_set FOR EACH ROW EXECUTE FUNCTION protect_risk_history();
CREATE TRIGGER immutable_integration_event BEFORE UPDATE OR DELETE ON integration_event FOR EACH ROW EXECUTE FUNCTION protect_risk_history();
