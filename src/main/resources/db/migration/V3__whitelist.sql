CREATE TABLE whitelist (
    id         BIGSERIAL PRIMARY KEY,
    user_id    VARCHAR(64) NOT NULL,
    channel    VARCHAR(32) NOT NULL,
    paired_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, channel)
);
