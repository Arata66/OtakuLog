package com.otakulog;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.ResultSet;
import java.sql.Statement;

public class V8__align_anime_status extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            // 先拒绝未知状态，避免非严格模式下转为 ENUM 时静默丢失原值。
            try (ResultSet invalid = statement.executeQuery("SELECT COUNT(*) FROM anime WHERE status IS NOT NULL "
                    + "AND BINARY status NOT IN ('WATCHING','FINISHED','PLANNING','DROPPED')")) {
                invalid.next();
                if (invalid.getLong(1) > 0) {
                    throw new IllegalStateException("番剧存在未知状态，请修正后重新执行迁移");
                }
            }
            String expected = "enum('WATCHING','FINISHED','PLANNING','DROPPED')";
            try (ResultSet column = statement.executeQuery("SHOW COLUMNS FROM anime WHERE Field = 'status'")) {
                column.next();
                // 旧自用库已经是 ENUM 时无需重复改表。
                if (expected.equals(column.getString("Type"))) {
                    return;
                }
            }
            statement.execute("ALTER TABLE anime MODIFY status " + expected + " DEFAULT NULL");
        }
    }
}
