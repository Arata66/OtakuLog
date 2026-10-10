CREATE TABLE anime_memory (
    id BIGINT NOT NULL AUTO_INCREMENT,
    anime_id BIGINT NOT NULL,
    memory_key VARCHAR(36) NOT NULL,
    content TEXT NOT NULL,
    liked TEXT NULL,
    disliked TEXT NULL,
    scope VARCHAR(200) NULL,
    context VARCHAR(20) NOT NULL,
    watched_date DATE NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_am_memory_key UNIQUE (memory_key),
    CONSTRAINT fk_am_anime FOREIGN KEY (anime_id) REFERENCES anime(id) ON DELETE CASCADE,
    INDEX idx_am_anime_created (anime_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
