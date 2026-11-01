-- Foundation only: business tables belong to subsequent implementation stages.
CREATE TABLE platform_metadata (
    key VARCHAR(64) PRIMARY KEY,
    value VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT platform_metadata_key_nonempty CHECK (key <> ''),
    CONSTRAINT platform_metadata_value_nonempty CHECK (value <> '')
);

INSERT INTO platform_metadata (key, value)
VALUES ('service_name', 'payment-intelligence-platform');
