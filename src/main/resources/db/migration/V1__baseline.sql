-- Flyway baseline: 当前数据库结构快照
-- 请根据实际数据库结构核实此脚本

CREATE TABLE IF NOT EXISTS `anime` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(255) NOT NULL,
    `current_episode` INT DEFAULT NULL,
    `total_episodes` INT DEFAULT NULL,
    `status` VARCHAR(20) DEFAULT NULL,
    `score` DOUBLE DEFAULT NULL,
    `season` VARCHAR(255) DEFAULT NULL,
    `remark` VARCHAR(255) DEFAULT NULL,
    `cover_url` VARCHAR(255) DEFAULT NULL,
    `start_date` DATE DEFAULT NULL,
    `end_date` DATE DEFAULT NULL,
    `tags` VARCHAR(500) DEFAULT NULL,
    `sort_order` INT DEFAULT NULL,
    `broadcast_day` INT DEFAULT NULL,
    `bangumi_id` INT DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
