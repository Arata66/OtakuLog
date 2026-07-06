-- V7: 添加观看季节字段
ALTER TABLE `anime` ADD COLUMN `watch_season` VARCHAR(20) DEFAULT NULL;
CREATE INDEX `idx_anime_watch_season` ON `anime`(`watch_season`);
