-- 追番时间追踪：追番开始日 + 旧番标记
ALTER TABLE anime ADD COLUMN watch_start_date DATE;
ALTER TABLE anime ADD COLUMN legacy TINYINT(1) NOT NULL DEFAULT 0;

-- 已有数据用 created_at 的日期部分回填 watch_start_date
UPDATE anime SET watch_start_date = DATE(created_at) WHERE watch_start_date IS NULL;
