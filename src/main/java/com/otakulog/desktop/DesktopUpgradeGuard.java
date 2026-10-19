package com.otakulog.desktop;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationInfoService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;

import static com.otakulog.desktop.DesktopStartupFailure.Code.*;

public final class DesktopUpgradeGuard implements AutoCloseable, ApplicationListener<ApplicationReadyEvent>, Ordered {
    private static final String LOCKED_H2_VERSION = "2.2.224";
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final Path dataDirectory, upgradeDirectory, database, journal;
    private FileChannel lockChannel;
    private FileLock directoryLock;
    private ObjectNode attempt;

    public DesktopUpgradeGuard(Path dataDirectory, Path backupDirectory) {
        this.dataDirectory = dataDirectory;
        this.upgradeDirectory = backupDirectory.resolve("upgrade");
        this.database = dataDirectory.resolve("otakulog.mv.db");
        this.journal = dataDirectory.resolve("desktop-upgrade.json");
        try {
            lockChannel = FileChannel.open(dataDirectory.resolve("desktop-upgrade.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            directoryLock = lockChannel.tryLock();
            if (directoryLock == null) throw failure(DATABASE_BUSY, "资料目录正被其他程序占用，请关闭其他实例", null);
            verifyEngine();
            verifyPreviousAttempt();
        } catch (DesktopStartupFailure failure) {
            close(); throw failure;
        } catch (OverlappingFileLockException failure) {
            close(); throw failure(DATABASE_BUSY, "资料目录正被其他程序占用，请关闭其他实例", failure);
        } catch (Exception failure) {
            close(); throw failure(UPGRADE_BLOCKED, "无法检查桌面升级保护状态，原资料保留", failure);
        }
    }

    public String jdbcUrl() {
        return "jdbc:h2:file:" + dataDirectory.resolve("otakulog").toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
    }

    private void verifyEngine() throws Exception {
        String runtime = (String) Class.forName("org.h2.engine.Constants").getField("VERSION").get(null);
        if (!LOCKED_H2_VERSION.equals(runtime)) {
            throw failure(ENGINE_MISMATCH, "当前程序的 H2 引擎不在已锁定版本内，请用旧程序导出后迁移", null);
        }
        Path marker = dataDirectory.resolve("desktop-engine.json");
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            JsonNode engine;
            try { engine = readState(marker); }
            catch (Exception failure) { throw failure(ENGINE_MISMATCH, "无法识别资料引擎标记，请用旧程序导出后迁移", failure); }
            if (!"H2".equals(engine.path("engine").asText()) || !runtime.equals(engine.path("version").asText())) {
                throw failure(ENGINE_MISMATCH, "资料与当前 H2 引擎版本不同，请用旧程序导出后迁移", null);
            }
        } else {
            // 无标记仅接纳尚未发布的固定引擎资料，不能据此推断任意旧引擎都兼容。
            var engine = json.createObjectNode().put("engine", "H2").put("version", runtime)
                    .put("legacyAssumed", Files.exists(database))
                    .put("compatibility", "仅接纳本项目尚未发布的固定 H2 2.2.224 资料，不代表跨引擎兼容");
            atomicState(marker, engine);
        }
    }

    private void verifyPreviousAttempt() throws Exception {
        if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) return;
        JsonNode previous = readState(journal);
        if (!"COMPLETED".equals(previous.path("state").asText())) {
            throw failure(UPGRADE_BLOCKED, "上次升级未完成，已阻止自动重试；请保留原资料并从独立目录核对升级前备份", null);
        }
    }

    public void migrate(Flyway flyway) {
        MigrationInfoService information;
        try { information = flyway.info(); }
        catch (RuntimeException failure) {
            boolean busy = false;
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getErrorCode() == 90020) busy = true;
            }
            throw failure(busy ? DATABASE_BUSY : UPGRADE_BLOCKED,
                    busy ? "数据库正被其他程序占用，请关闭其他实例" : "无法读取资料结构，已停止升级且保留原资料", failure);
        }
        boolean newer = Arrays.stream(information.all()).anyMatch(migration ->
                migration.getState() == MigrationState.FUTURE_SUCCESS || migration.getState() == MigrationState.FUTURE_FAILED);
        // 独立 validate 默认拒绝待执行版本；只在预检时容许 pending，仍拒绝未知的已执行版本。
        try { Flyway.configure().configuration(flyway.getConfiguration()).ignoreMigrationPatterns("*:pending").load().validate(); }
        catch (RuntimeException failure) {
            throw failure(newer ? SCHEMA_NEWER : UPGRADE_BLOCKED,
                    newer ? "资料结构比当前程序更新，请使用匹配的新版本" : "资料结构校验失败，已停止升级且保留原资料", failure);
        }
        boolean pending = information.pending().length > 0;
        boolean upgrade = information.current() != null && information.current().getVersion() != null && pending;
        Path backup = upgradeDirectory;
        if (upgrade) {
            System.out.println("OTAKULOG_DESKTOP_UPGRADING");
            System.out.flush();
            backup = snapshot(information.current().getVersion().getVersion(),
                    information.pending()[information.pending().length - 1].getVersion().getVersion());
        }
        if (pending) {
            try {
                attempt = json.createObjectNode().put("state", "ATTEMPTING").put("backupDirectory", backup.toString())
                        .put("backupAvailable", upgrade).put("engineVersion", LOCKED_H2_VERSION).put("startedAt", Instant.now().toString());
                // H2 的 DDL 可能已提交一部分，持久记录必须先于第一条迁移语句。
                atomicState(journal, attempt);
            } catch (Exception failure) {
                throw failure(BACKUP_FAILED, "无法保存升级保护记录，尚未执行结构迁移", failure);
            }
        }
        try { flyway.migrate(); }
        catch (RuntimeException failure) {
            throw failure(UPGRADE_BLOCKED, "数据库初始化或升级未完成，原目录与已有备份均保留，已阻止下次自动重试", failure);
        }
    }

    private Path snapshot(String sourceVersion, String targetVersion) {
        Path destination = upgradeDirectory.resolve(Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID());
        try {
            rejectSymbolicDirectories(upgradeDirectory);
            Files.createDirectories(upgradeDirectory);
            Files.createDirectory(destination);
            Path sql = destination.resolve("database.sql");
            try (var connection = DriverManager.getConnection(jdbcUrl(), "sa", ""); var statement = connection.createStatement()) {
                statement.execute("SCRIPT TO '" + sqlPath(sql) + "'");
            }
            force(sql);
            verifySql(sql, destination);
            // 无连接池参与迁移；最后一条连接关闭后，再核对引擎文件锁。
            try (var source = FileChannel.open(database, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 var stopped = source.tryLock()) {
                if (stopped == null) throw failure(DATABASE_BUSY, "数据库仍被其他程序占用，尚未执行结构迁移", null);
                Path copy = destination.resolve("otakulog.mv.db");
                String stoppedDigest = copyStopped(source, copy);
                if (!stoppedDigest.equals(sha(copy))) throw new IOException("停库快照校验不一致");
                Files.copy(dataDirectory.resolve("desktop-engine.json"), destination.resolve("desktop-engine.json"));
                force(destination.resolve("desktop-engine.json"));
                var metadata = json.createObjectNode().put("engine", "H2").put("engineVersion", LOCKED_H2_VERSION)
                        .put("sourceVersion", sourceVersion).put("targetVersion", targetVersion)
                        .put("createdAt", Instant.now().toString()).put("databaseSha256", sha(copy)).put("sqlSha256", sha(sql));
                atomicState(destination.resolve("metadata.json"), metadata);
            }
            return destination;
        } catch (DesktopStartupFailure failure) { throw failure; }
        catch (OverlappingFileLockException failure) {
            throw failure(DATABASE_BUSY, "数据库仍被其他程序占用，尚未执行结构迁移", failure);
        } catch (Exception failure) {
            // SCRIPT/RUNSCRIPT 的异常可能包含用户记录整句，不能交给启动日志。
            throw failure(BACKUP_FAILED, "升级前导出、恢复验证或停库备份失败，尚未执行结构迁移", null);
        }
    }

    private void verifySql(Path sql, Path destination) throws Exception {
        Path verification = Files.createTempDirectory(destination, ".verify-");
        try {
            String url = "jdbc:h2:file:" + verification.resolve("verification").toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE";
            try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
                statement.execute("RUNSCRIPT FROM '" + sqlPath(sql) + "'");
            }
        } finally {
            // 只清理本次成功创建的隔离验证库，不接触原目录与已有备份。
            try (var files = Files.walk(verification)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private void rejectSymbolicDirectories(Path directory) throws IOException {
        for (Path current = directory; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("升级备份目录不能使用符号链接");
        }
    }

    private JsonNode readState(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16_384) throw new IOException("升级状态文件无效");
        JsonNode state = json.readTree(Files.readAllBytes(path));
        if (state == null || !state.isObject()) throw new IOException("升级状态文件无效");
        return state;
    }

    private void atomicState(Path destination, JsonNode state) throws IOException {
        Path temporary = destination.resolveSibling(".otakulog-upgrade-" + UUID.randomUUID() + ".tmp");
        try {
            byte[] bytes = json.writeValueAsBytes(state);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (!Arrays.equals(bytes, Files.readAllBytes(temporary))) throw new IOException("升级状态写入校验失败");
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private void force(Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    private String copyStopped(FileChannel source, Path destination) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var target = FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.allocate(65_536);
            source.position(0);
            // Windows 文件锁也约束本进程的新句柄，必须使用已持锁的通道读取。
            while (source.read(buffer) != -1) {
                buffer.flip();
                digest.update(buffer.asReadOnlyBuffer());
                while (buffer.hasRemaining()) target.write(buffer);
                buffer.clear();
            }
            target.force(true);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String sha(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65_536];
            for (int length; (length = input.read(buffer)) != -1; ) digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String sqlPath(Path path) { return path.toString().replace('\\', '/').replace("'", "''"); }

    private DesktopStartupFailure failure(DesktopStartupFailure.Code code, String message, Throwable cause) {
        return new DesktopStartupFailure(code, message, dataDirectory, upgradeDirectory, cause);
    }

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override public void onApplicationEvent(ApplicationReadyEvent event) {
        if (attempt == null) return;
        try { atomicState(journal, attempt.deepCopy().put("state", "COMPLETED").put("completedAt", Instant.now().toString())); }
        catch (Exception failure) { throw failure(UPGRADE_BLOCKED, "无法确认升级完成，已保留保护记录并停止启动", failure); }
        attempt = null;
    }

    @Override public void close() {
        try { if (directoryLock != null && directoryLock.isValid()) directoryLock.release(); } catch (IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
    }
}
