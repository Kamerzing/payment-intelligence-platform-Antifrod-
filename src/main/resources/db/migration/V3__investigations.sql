ALTER TABLE integration_event ALTER COLUMN operation_id DROP NOT NULL;
ALTER TABLE integration_event ADD COLUMN attempt_id UUID;
CREATE UNIQUE INDEX integration_rejection_attempt ON integration_event(source_id,attempt_id,source_version) WHERE operation_id IS NULL;

CREATE TABLE investigation (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	attempt_id UUID NOT NULL UNIQUE REFERENCES risk_attempt(attempt_id),
	status VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','IN_REVIEW','CLOSED')),
	conclusion VARCHAR(24) CHECK(conclusion IN ('SUSPICIOUS','LEGITIMATE','INSUFFICIENT_DATA')),
	version INTEGER NOT NULL DEFAULT 1 CHECK(version>0),
	created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
	updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
	CHECK ((status='CLOSED') = (conclusion IS NOT NULL))
);
CREATE INDEX investigation_queue ON investigation(status,updated_at DESC,id);
CREATE TABLE investigation_history (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	investigation_id UUID NOT NULL REFERENCES investigation(id),
	actor_id UUID,
	status VARCHAR(12) NOT NULL,
	conclusion VARCHAR(24),
	comment VARCHAR(2000) NOT NULL DEFAULT '',
	version INTEGER NOT NULL,
	created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
	UNIQUE(investigation_id,version)
);
CREATE TRIGGER immutable_investigation_history BEFORE UPDATE OR DELETE ON investigation_history FOR EACH ROW EXECUTE FUNCTION protect_risk_history();
CREATE FUNCTION open_risk_investigation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE case_id UUID;
BEGIN
	INSERT INTO investigation(attempt_id) VALUES(NEW.attempt_id) ON CONFLICT DO NOTHING RETURNING id INTO case_id;
	IF case_id IS NOT NULL THEN INSERT INTO investigation_history(investigation_id,status,version) VALUES(case_id,'OPEN',1); END IF;
	RETURN NEW;
END $$;
CREATE TRIGGER risk_denial_investigation AFTER INSERT ON risk_evaluation FOR EACH ROW WHEN(NEW.decision='DENY') EXECUTE FUNCTION open_risk_investigation();
INSERT INTO investigation(attempt_id) SELECT DISTINCT attempt_id FROM risk_evaluation WHERE decision='DENY';
INSERT INTO investigation_history(investigation_id,status,version) SELECT id,status,version FROM investigation;
