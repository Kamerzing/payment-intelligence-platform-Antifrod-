CREATE TABLE ai_analysis (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	attempt_id UUID NOT NULL REFERENCES risk_attempt(attempt_id),
	actor_id UUID NOT NULL,
	request_key UUID NOT NULL,
	status VARCHAR(12) NOT NULL CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FALLBACK')),
	reason VARCHAR(40),
	facts TEXT NOT NULL,
	report TEXT NOT NULL,
	metadata TEXT NOT NULL,
	created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
	started_at TIMESTAMPTZ,
	finished_at TIMESTAMPTZ,
	lease_until TIMESTAMPTZ,
	UNIQUE(actor_id,request_key),
	CHECK ((status IN ('SUCCEEDED','FALLBACK')) = (finished_at IS NOT NULL))
);
CREATE UNIQUE INDEX ai_one_active_attempt ON ai_analysis(attempt_id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX ai_queue ON ai_analysis(status,created_at);
CREATE INDEX ai_attempt_history ON ai_analysis(attempt_id,created_at DESC);
CREATE FUNCTION protect_ai_analysis() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	IF TG_OP='DELETE' THEN RAISE EXCEPTION 'AI history is immutable' USING ERRCODE='23514'; END IF;
	IF OLD.status IN ('SUCCEEDED','FALLBACK') OR
	 ROW(OLD.id,OLD.attempt_id,OLD.actor_id,OLD.request_key,OLD.facts,OLD.created_at) IS DISTINCT FROM
	 ROW(NEW.id,NEW.attempt_id,NEW.actor_id,NEW.request_key,NEW.facts,NEW.created_at) THEN
		RAISE EXCEPTION 'AI history is immutable' USING ERRCODE='23514';
	END IF;
	RETURN NEW;
END $$;
CREATE TRIGGER immutable_ai_analysis BEFORE UPDATE OR DELETE ON ai_analysis FOR EACH ROW EXECUTE FUNCTION protect_ai_analysis();
