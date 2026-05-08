CREATE TABLE IF NOT EXISTS anime_group (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    color VARCHAR(20),
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS anime_group_relation (
    group_id BIGINT NOT NULL,
    anime_id BIGINT NOT NULL,
    PRIMARY KEY (group_id, anime_id),
    FOREIGN KEY (group_id) REFERENCES anime_group(id) ON DELETE CASCADE,
    FOREIGN KEY (anime_id) REFERENCES anime(id) ON DELETE CASCADE
);
