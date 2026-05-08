-- 添加索引
ALTER TABLE `anime` ADD INDEX `idx_anime_name` (`name`);
ALTER TABLE `anime` ADD INDEX `idx_anime_status` (`status`);
ALTER TABLE `anime` ADD INDEX `idx_anime_broadcast_day` (`broadcast_day`);

-- 添加审计字段
ALTER TABLE `anime` ADD COLUMN `created_at` DATETIME(6) DEFAULT NULL;
ALTER TABLE `anime` ADD COLUMN `updated_at` DATETIME(6) DEFAULT NULL;
