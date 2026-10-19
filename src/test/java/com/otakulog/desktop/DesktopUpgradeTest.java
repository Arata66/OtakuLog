package com.otakulog.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DesktopUpgradeTest {
    @TempDir Path temporaryDirectory;

    @Test
    void 当空库首次初始化部分失败时应该保留原目录并阻断下次自动重试() throws Exception {
        Path data = Files.createDirectory(temporaryDirectory.resolve("初始化失败资料"));
        Path migrations = Files.createDirectory(temporaryDirectory.resolve("初始化失败迁移"));
        Files.writeString(migrations.resolve("V1__broken_initial.sql"),
                "CREATE TABLE partial_initial(id INT);\nINSERT INTO missing_table VALUES (1);");
        assertThrows(RuntimeException.class, () -> start(data, migrations));
        String before = sha(data.resolve("otakulog.mv.db"));
        RuntimeException failure = assertThrows(RuntimeException.class, () -> start(data, migrations));
        assertTrue(messages(failure).contains("上次升级未完成"), messages(failure));
        assertEquals(before, sha(data.resolve("otakulog.mv.db")));
    }

    @Test
    void 当同引擎旧结构升级成功时应该保留可独立恢复的停库快照和SQL() throws Exception {
        Path data = temporaryDirectory.resolve("资料 空格");
        Path migrations = oldDatabase(data);
        Files.writeString(migrations.resolve("V2__new_column.sql"),
                "ALTER TABLE fictional_records ADD COLUMN upgraded BOOLEAN DEFAULT TRUE;");
        Path backup;
        try (var context = start(data, migrations)) {
            assertEquals("虚构升级记录", value(data, "SELECT name FROM fictional_records"));
            assertEquals("TRUE", value(data, "SELECT upgraded FROM fictional_records"));
            assertTrue(Files.isDirectory(data.resolve("backups/upgrade")), "结构迁移前应该保留升级备份");
            backup = onlyBackup(data);
            assertTrue(Files.isRegularFile(backup.resolve("otakulog.mv.db")));
            assertTrue(Files.isRegularFile(backup.resolve("database.sql")));
            var metadata = new ObjectMapper().readTree(backup.resolve("metadata.json").toFile());
            assertEquals(sha(backup.resolve("otakulog.mv.db")), metadata.path("databaseSha256").asText());
            assertEquals(sha(backup.resolve("database.sql")), metadata.path("sqlSha256").asText());
            assertTrue(Files.readString(data.resolve("desktop-upgrade.json")).contains("ATTEMPTING"));
            ready(context);
            assertTrue(Files.readString(data.resolve("desktop-upgrade.json")).contains("COMPLETED"));
        }
        Path recovered = Files.createDirectory(temporaryDirectory.resolve("独立快照恢复"));
        Files.copy(backup.resolve("otakulog.mv.db"), recovered.resolve("otakulog.mv.db"));
        assertEquals("虚构升级记录", value(recovered, "SELECT name FROM fictional_records"));
        assertEquals("0", value(recovered, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='FICTIONAL_RECORDS' AND COLUMN_NAME='UPGRADED'"));
        Path sqlRecovered = Files.createDirectory(temporaryDirectory.resolve("独立SQL恢复"));
        try (var connection = DriverManager.getConnection(url(sqlRecovered), "sa", ""); var statement = connection.createStatement()) {
            statement.execute("RUNSCRIPT FROM '" + sqlPath(backup.resolve("database.sql")) + "'");
        }
        assertEquals("虚构升级记录", value(sqlRecovered, "SELECT name FROM fictional_records"));
        try (var context = start(data, migrations)) { ready(context); }
        assertEquals(backup, onlyBackup(data));
    }

    @Test
    void 当迁移部分执行失败时应该保留原目录与快照并阻断下次自动重试() throws Exception {
        Path data = temporaryDirectory.resolve("失败资料");
        Path migrations = oldDatabase(data);
        Files.writeString(migrations.resolve("V2__broken.sql"),
                "ALTER TABLE fictional_records ADD COLUMN partial_marker INT;\nINSERT INTO missing_table VALUES (1);");
        RuntimeException failure = assertThrows(RuntimeException.class, () -> start(data, migrations));
        assertTrue(messages(failure).contains("升级"), messages(failure));
        assertEquals("虚构升级记录", value(data, "SELECT name FROM fictional_records"));
        assertEquals("1", value(data, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='FICTIONAL_RECORDS' AND COLUMN_NAME='PARTIAL_MARKER'"));
        Path backup = onlyBackup(data);
        String before = sha(data.resolve("otakulog.mv.db"));
        RuntimeException second = assertThrows(RuntimeException.class, () -> start(data, migrations));
        assertTrue(messages(second).contains("未完成"), messages(second));
        assertEquals(before, sha(data.resolve("otakulog.mv.db")));
        assertEquals(backup, onlyBackup(data));
        Path recovered = Files.createDirectory(temporaryDirectory.resolve("失败前快照恢复"));
        Files.copy(backup.resolve("otakulog.mv.db"), recovered.resolve("otakulog.mv.db"));
        assertEquals("虚构升级记录", value(recovered, "SELECT name FROM fictional_records"));
        assertEquals("0", value(recovered, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='FICTIONAL_RECORDS' AND COLUMN_NAME='PARTIAL_MARKER'"));
    }

    @Test
    void 当升级成功但尚未报告就绪即退出时应该阻断下次自动迁移() throws Exception {
        Path data = temporaryDirectory.resolve("中断资料");
        Path migrations = oldDatabase(data);
        Files.writeString(migrations.resolve("V2__new_column.sql"), "ALTER TABLE fictional_records ADD COLUMN upgraded BOOLEAN;");
        try (var ignored = start(data, migrations)) { }
        String before = sha(data.resolve("otakulog.mv.db"));
        RuntimeException failure = assertThrows(RuntimeException.class, () -> start(data, migrations));
        assertTrue(messages(failure).contains("未完成"), messages(failure));
        assertEquals(before, sha(data.resolve("otakulog.mv.db")));
    }

    @Test
    void 当引擎标记不匹配时应该在开库前拒绝且数据库字节不变() throws Exception {
        Path data = temporaryDirectory.resolve("引擎资料");
        Path migrations = oldDatabase(data);
        try (var context = start(data, migrations)) { ready(context); }
        Files.writeString(data.resolve("desktop-engine.json"), "{\"engine\":\"H2\",\"version\":\"unknown-old-engine\"}");
        String before = sha(data.resolve("otakulog.mv.db"));
        RuntimeException failure = assertThrows(RuntimeException.class, () -> start(data, migrations));
        assertTrue(messages(failure).contains("旧程序导出"), messages(failure));
        assertEquals(before, sha(data.resolve("otakulog.mv.db")));
    }

    @Test
    void 当资料目录已由其他程序持锁时应该拒绝开库且保留数据库() throws Exception {
        Path data = temporaryDirectory.resolve("占用资料");
        Path migrations = oldDatabase(data);
        String before = sha(data.resolve("otakulog.mv.db"));
        try (var channel = FileChannel.open(data.resolve("desktop-upgrade.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> start(data, migrations));
            assertTrue(messages(failure).contains("占用"), messages(failure));
        }
        assertEquals(before, sha(data.resolve("otakulog.mv.db")));
    }

    @Test
    void 当升级备份位置不可写时应该在迁移前停止并保留旧结构() throws Exception {
        Path data = temporaryDirectory.resolve("备份失败资料");
        Path migrations = oldDatabase(data);
        Files.writeString(migrations.resolve("V2__new_column.sql"), "ALTER TABLE fictional_records ADD COLUMN upgraded BOOLEAN;");
        Path blocked = Files.writeString(data.resolve("backups"), "虚构占位文件");
        RuntimeException failure = assertThrows(RuntimeException.class, () -> start(data, migrations));
        assertTrue(messages(failure).contains("备份"), messages(failure));
        assertEquals("0", value(data, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='FICTIONAL_RECORDS' AND COLUMN_NAME='UPGRADED'"));
        assertEquals("虚构占位文件", Files.readString(blocked));
    }

    private Path oldDatabase(Path data) throws Exception {
        Files.createDirectories(data);
        Path migrations = Files.createTempDirectory(temporaryDirectory, "migrations-");
        Files.writeString(migrations.resolve("V1__initial.sql"),
                "CREATE TABLE fictional_records(id INT PRIMARY KEY, name VARCHAR(80));\nINSERT INTO fictional_records VALUES(1, '虚构升级记录');");
        Flyway.configure().dataSource(url(data), "sa", "").locations("filesystem:" + migrations.toString().replace('\\', '/')).load().migrate();
        return migrations;
    }

    private AnnotationConfigApplicationContext start(Path data, Path migrations) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("desktop");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("隔离升级测试", Map.of(
                "app.desktop.data-dir", data.toString(), "spring.flyway.locations", "filesystem:" + migrations.toString().replace('\\', '/'))));
        context.register(DesktopDataDirectoryConfiguration.class, FlywayAutoConfiguration.class);
        try { context.refresh(); return context; }
        catch (RuntimeException failure) { context.close(); throw failure; }
    }

    private void ready(AnnotationConfigApplicationContext context) {
        context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
    }
    private String value(Path data, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(url(data), "sa", ""); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next()); return result.getString(1);
        }
    }
    private Path onlyBackup(Path data) throws Exception {
        try (var directories = Files.list(data.resolve("backups/upgrade"))) {
            var backups = directories.filter(Files::isDirectory).toList(); assertEquals(1, backups.size()); return backups.get(0);
        }
    }
    private String sha(Path file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
    private String url(Path data) { return "jdbc:h2:file:" + data.resolve("otakulog").toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"; }
    private String sqlPath(Path file) { return file.toString().replace('\\', '/').replace("'", "''"); }
    private String messages(Throwable failure) {
        var text = new StringBuilder(); for (Throwable cause = failure; cause != null; cause = cause.getCause()) text.append(cause.getMessage()).append('\n'); return text.toString();
    }
}
