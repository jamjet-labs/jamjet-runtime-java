-- Workflow definitions
CREATE TABLE IF NOT EXISTS workflows (
    workflow_id VARCHAR(255) NOT NULL,
    version     VARCHAR(64)  NOT NULL,
    ir          TEXT         NOT NULL,
    tenant_id   VARCHAR(255) NOT NULL DEFAULT 'default',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (workflow_id, version)
);

-- Workflow executions
CREATE TABLE IF NOT EXISTS executions (
    execution_id    VARCHAR(64)  NOT NULL PRIMARY KEY,
    workflow_id     VARCHAR(255) NOT NULL,
    workflow_version VARCHAR(64) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'pending',
    initial_input   TEXT         NOT NULL,
    current_state   TEXT         NOT NULL,
    session_type    VARCHAR(32),
    started_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_executions_status ON executions(status);

-- Event log (append-only)
CREATE TABLE IF NOT EXISTS events (
    id            VARCHAR(64) NOT NULL PRIMARY KEY,
    execution_id  VARCHAR(64) NOT NULL,
    sequence      BIGINT      NOT NULL,
    kind          TEXT        NOT NULL,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (execution_id, sequence)
);
CREATE INDEX IF NOT EXISTS idx_events_exec_seq ON events(execution_id, sequence);

-- Snapshots
CREATE TABLE IF NOT EXISTS snapshots (
    id            VARCHAR(64) NOT NULL PRIMARY KEY,
    execution_id  VARCHAR(64) NOT NULL,
    at_sequence   BIGINT      NOT NULL,
    state         TEXT        NOT NULL,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_snapshots_exec ON snapshots(execution_id, at_sequence DESC);

-- Work items
CREATE TABLE IF NOT EXISTS work_items (
    id               VARCHAR(64)  NOT NULL PRIMARY KEY,
    execution_id     VARCHAR(64)  NOT NULL,
    node_id          VARCHAR(255) NOT NULL,
    queue_type       VARCHAR(32)  NOT NULL,
    payload          TEXT         NOT NULL,
    attempt          INT          NOT NULL DEFAULT 1,
    max_attempts     INT          NOT NULL DEFAULT 3,
    status           VARCHAR(16)  NOT NULL DEFAULT 'pending',
    tenant_id        VARCHAR(255) NOT NULL DEFAULT 'default',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_expires_at TIMESTAMP,
    worker_id        VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_work_items_claim ON work_items(status, queue_type, created_at);

-- API tokens
CREATE TABLE IF NOT EXISTS api_tokens (
    id         VARCHAR(64)  NOT NULL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    role       VARCHAR(32)  NOT NULL,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    tenant_id  VARCHAR(255) NOT NULL DEFAULT 'default',
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP
);

-- Tenants
CREATE TABLE IF NOT EXISTS tenants (
    id         VARCHAR(255) NOT NULL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'active',
    policy     TEXT,
    limits     TEXT,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
