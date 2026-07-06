-- V6: 标签系统重构（Schema 部分）
-- 新建 tag 表和 anime_tag 关联表

CREATE TABLE `tag` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(50) NOT NULL UNIQUE,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `anime_tag` (
    `anime_id` BIGINT NOT NULL,
    `tag_id` BIGINT NOT NULL,
    PRIMARY KEY (`anime_id`, `tag_id`),
    FOREIGN KEY (`anime_id`) REFERENCES `anime`(`id`) ON DELETE CASCADE,
    FOREIGN KEY (`tag_id`) REFERENCES `tag`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX `idx_anime_tag_tag_id` ON `anime_tag`(`tag_id`);
