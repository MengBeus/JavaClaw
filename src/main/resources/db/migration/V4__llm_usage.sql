CREATE TABLE IF NOT EXISTS llm_usage (
    id          BIGSERIAL PRIMARY KEY,
    session_id  TEXT,
    provider    TEXT NOT NULL,
    model       TEXT,
    prompt_tokens     INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    cost_usd    NUMERIC(12,6) NOT NULL DEFAULT 0,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_llm_usage_created ON llm_usage(created_at);
