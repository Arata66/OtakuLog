CREATE TABLE IF NOT EXISTS episode_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    anime_id BIGINT NOT NULL,
    episode_number INT NOT NULL COMMENT '第几集，从1开始',
    watched_date DATE NOT NULL COMMENT '观看日期',
    created_at DATETIME(6) DEFAULT NULL,
    updated_at DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_anime_episode (anime_id, episode_number),
    KEY idx_er_watched_date (watched_date),
    CONSTRAINT fk_er_anime FOREIGN KEY (anime_id) REFERENCES anime(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每集观看记录，驱动热力图';

-- 为已有数据回填 episode_record
-- 从 watchStartDate 开始，每集递增一天，模拟逐日观看
INSERT INTO episode_record (anime_id, episode_number, watched_date, created_at, updated_at)
SELECT
    a.id,
    seq.n,
    DATE_ADD(COALESCE(a.watch_start_date, DATE(a.created_at), CURDATE()), INTERVAL (seq.n - 1) DAY),
    NOW(6), NOW(6)
FROM anime a
CROSS JOIN (
    SELECT @rownum := @rownum + 1 AS n
    FROM information_schema.columns, (SELECT @rownum := 0) r
    LIMIT 200
) seq
WHERE a.current_episode > 0
  AND a.legacy = 0
  AND seq.n <= a.current_episode
  AND NOT EXISTS (SELECT 1 FROM episode_record er WHERE er.anime_id = a.id);
