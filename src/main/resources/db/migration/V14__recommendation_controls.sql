CREATE TABLE recommendation_preference (
 id BIGINT NOT NULL PRIMARY KEY,
 preference_key VARCHAR(36) NOT NULL UNIQUE,
 version BIGINT NOT NULL DEFAULT 0,
 min_year INT NULL, max_year INT NULL,
 excluded_tags_json TEXT NOT NULL, recall_tags_json TEXT NOT NULL,
 include_tracked BIT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE recommendation_feedback (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 feedback_key VARCHAR(36) NOT NULL UNIQUE,
 owner VARCHAR(100) NOT NULL,
 source_batch_key VARCHAR(36) NOT NULL,
 subject_id INT NOT NULL, name VARCHAR(1000) NOT NULL,
 kind VARCHAR(20) NOT NULL, active BIT NOT NULL DEFAULT 1,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 INDEX idx_feedback_subject(subject_id,active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE recommendation_shown (
 subject_id INT NOT NULL PRIMARY KEY,
 shown_at DATETIME(6) NOT NULL,
 batch_key VARCHAR(36) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
