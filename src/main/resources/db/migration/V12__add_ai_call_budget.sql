CREATE TABLE ai_call (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_key VARCHAR(36) NOT NULL,
    memory_key VARCHAR(36) NOT NULL,
    source_version BIGINT NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    model VARCHAR(80) NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,
    selected_characters INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    history_only TINYINT(1) NOT NULL DEFAULT 0,
    error_category VARCHAR(30) NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    cached_tokens INT NULL,
    reasoning_tokens INT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ai_request_key UNIQUE (request_key),
    CONSTRAINT ck_ai_nonnegative CHECK (source_version >= 0 AND selected_characters BETWEEN 1 AND 6000
        AND (prompt_tokens IS NULL OR prompt_tokens >= 0) AND (completion_tokens IS NULL OR completion_tokens >= 0)
        AND (cached_tokens IS NULL OR cached_tokens >= 0) AND (reasoning_tokens IS NULL OR reasoning_tokens >= 0)),
    INDEX idx_ai_created (created_at),
    INDEX idx_ai_cache (memory_key, source_version, input_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_quota (
    id BIGINT NOT NULL,
    quota_date DATE NOT NULL,
    reserved_calls INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT ck_ai_quota_singleton CHECK (id = 1 AND reserved_calls >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO ai_quota (id, quota_date, reserved_calls) VALUES (1, CURRENT_DATE(), 0);
