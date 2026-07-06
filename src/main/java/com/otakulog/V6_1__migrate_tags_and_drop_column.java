package com.otakulog;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

// 将 anime.tags 逗号分隔字符串迁移到 tag + anime_tag 表，然后删除旧列
public class V6_1__migrate_tags_and_drop_column extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(conn, true));

        // 读取所有有 tags 的番剧
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, tags FROM anime WHERE tags IS NOT NULL AND TRIM(tags) != ''");

        for (Map<String, Object> row : rows) {
            Long animeId = ((Number) row.get("id")).longValue();
            String tagsStr = (String) row.get("tags");
            if (tagsStr == null || tagsStr.trim().isEmpty()) continue;

            String[] tagNames = tagsStr.split(",");
            for (String raw : tagNames) {
                String tagName = raw.trim();
                if (tagName.isEmpty()) continue;

                // 插入或获取 tag ID
                Long tagId = findOrCreateTag(jdbc, tagName);

                // 关联 anime 和 tag（忽略重复）
                jdbc.update("INSERT IGNORE INTO anime_tag (anime_id, tag_id) VALUES (?, ?)",
                        animeId, tagId);
            }
        }

        // 删除旧列
        jdbc.execute("ALTER TABLE anime DROP COLUMN tags");
    }

    private Long findOrCreateTag(JdbcTemplate jdbc, String name) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM tag WHERE name = ?", name);
        if (!existing.isEmpty()) {
            return ((Number) existing.get(0).get("id")).longValue();
        }
        jdbc.update("INSERT INTO tag (name) VALUES (?)", name);
        List<Map<String, Object>> created = jdbc.queryForList(
                "SELECT id FROM tag WHERE name = ?", name);
        return ((Number) created.get(0).get("id")).longValue();
    }
}
