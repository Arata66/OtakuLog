CREATE TABLE import_lock (id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO import_lock(id) VALUES (1);
CREATE TABLE external_collection (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    collection_key VARCHAR(36) NOT NULL,
    username VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    subject_id INT NOT NULL,
    collection_type INT,
    payload TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    captured_at DATETIME(6) NOT NULL,
    created_at DATETIME(6), updated_at DATETIME(6),
    CONSTRAINT uk_ec_key UNIQUE (collection_key),
    CONSTRAINT uk_ec_identity UNIQUE (username, subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE import_batch (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_key VARCHAR(36) NOT NULL,
    owner VARCHAR(100) NOT NULL,
    username VARCHAR(100) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    result_json TEXT NOT NULL,
    created_at DATETIME(6), updated_at DATETIME(6),
    CONSTRAINT uk_ib_request UNIQUE (request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
