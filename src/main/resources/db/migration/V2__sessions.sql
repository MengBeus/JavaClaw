CREATE TABLE sessions (
    id         VARCHAR(64) PRIMARY KEY,
    user_id    VARCHAR(64),
    channel_id VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chat_messages (
    id         BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    role       VARCHAR(16) NOT NULL,
    content    TEXT,
    tool_call_id VARCHAR(64),
    metadata   JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_messages_session ON chat_messages(session_id, id);
