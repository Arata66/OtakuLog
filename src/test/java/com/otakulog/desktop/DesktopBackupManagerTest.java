package com.otakulog.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DesktopBackupManagerTest {
    @TempDir Path directory;
    private final AtomicInteger changes = new AtomicInteger();
    private final MutableClock clock = new MutableClock();
    private String snapshot() {
        return "{\"format\":\"otakulog-backup\",\"version\":6,\"exportedAt\":\"" + clock.instant()
                + "\",\"anime\":[{\"fictional\":" + changes.get() + "}],\"tags\":[],\"groups\":[],\"memberships\":[],\"episodes\":[],\"memories\":[],\"insights\":[],"
                + "\"aiCalls\":[],\"externalCollections\":[],\"importBatches\":[],\"recommendationPreferences\":[],\"recommendationFeedback\":[],\"recommendationBases\":[]}";
    }
    private void validateFixture(String value) {
        try {
            var document = new ObjectMapper().readTree(value);
            if (!document.path("exportedAt").isTextual() || !document.path("anime").isArray()) throw new IllegalArgumentException("虚构备份字段缺失");
        } catch (java.io.IOException failure) { throw new IllegalArgumentException("虚构备份损坏"); }
    }
    @Test void 当没有实际变化或事务回滚时应该不创建每日备份() throws Exception {
        try (var manager = new DesktopBackupManager(directory, clock, this::snapshot, this::validateFixture)) {
            manager.initialize(); manager.committed(); manager.flush();
            assertFalse(Files.exists(directory.resolve("daily")));
            assertNull(manager.status().lastSuccessfulDay());
        }
    }
    @Test void 当同日多次修改并重启时应该只保存第一份成功快照() throws Exception {
        Path file = directory.resolve("daily/2026-10-19.json");
        try (var manager = new DesktopBackupManager(directory, clock, this::snapshot, this::validateFixture)) {
            manager.initialize(); changes.incrementAndGet(); manager.committed(); manager.flush();
            String first = Files.readString(file);
            changes.incrementAndGet(); manager.committed(); manager.flush();
            assertEquals(first, Files.readString(file));
            assertEquals(LocalDate.of(2026, 10, 19), manager.status().lastSuccessfulDay());
        }
        try (var reopened = new DesktopBackupManager(directory, clock, this::snapshot, this::validateFixture)) {
            reopened.initialize(); changes.incrementAndGet(); reopened.committed(); reopened.flush();
            assertEquals(1, new ObjectMapper().readTree(Files.readString(file)).path("anime").get(0).path("fictional").asInt());
        }
    }
    @Test void 当连续多天修改时应该只保留七份每日备份并保留独立升级文件() throws Exception {
        Files.createDirectories(directory.resolve("upgrade"));
        Files.writeString(directory.resolve("upgrade/旧版.json"), "升级前原资料");
        try (var manager = new DesktopBackupManager(directory, clock, this::snapshot, this::validateFixture)) {
            manager.initialize();
            for (int day = 19; day <= 28; day++) {
                clock.day(day); changes.incrementAndGet(); manager.committed(); manager.flush();
            }
            try (var files = Files.list(directory.resolve("daily"))) { assertEquals(7, files.count()); }
            assertFalse(Files.exists(directory.resolve("daily/2026-10-21.json")));
            assertTrue(Files.exists(directory.resolve("daily/2026-10-22.json")));
            assertEquals("升级前原资料", Files.readString(directory.resolve("upgrade/旧版.json")));
            clock.day(29); manager.committed(); manager.flush();
            assertFalse(Files.exists(directory.resolve("daily/2026-10-29.json")), "跨日但内容未修改不能新增备份");
        }
    }
    @Test void 当备份目标不可替换时应该保留旧文件并允许之后重试() throws Exception {
        Path day = directory.resolve("daily/2026-10-19.json");
        Files.createDirectories(day); Files.writeString(day.resolve("保留.txt"), "不能丢失");
        try (var manager = new DesktopBackupManager(directory, clock, this::snapshot, this::validateFixture)) {
            manager.initialize(); changes.incrementAndGet(); manager.committed(); manager.flush();
            assertNotNull(manager.status().warning());
            assertEquals("不能丢失", Files.readString(day.resolve("保留.txt")));
            Files.delete(day.resolve("保留.txt")); Files.delete(day);
            manager.committed(); manager.flush();
            assertTrue(Files.isRegularFile(day)); assertNull(manager.status().warning());
            try (var files = Files.list(directory.resolve("daily"))) { assertEquals(1, files.count()); }
        }
    }
    @Test void 当导出内容损坏时应该保留已有备份且不标记成功() throws Exception {
        AtomicInteger state = new AtomicInteger();
        try (var manager = new DesktopBackupManager(directory, clock, () -> state.get() == 0 ? snapshot() : "{损坏", this::validateFixture)) {
            manager.initialize(); state.incrementAndGet(); manager.committed(); manager.flush();
            assertNotNull(manager.status().warning()); assertNull(manager.status().lastSuccessfulDay());
            assertFalse(Files.exists(directory.resolve("daily/2026-10-19.json")));
        }
    }
    @Test void 当启动时备份目录损坏但之后修正时应该在下次修改自动重试() throws Exception {
        Files.writeString(directory.resolve("daily"), "阻塞目录的虚构文件");
        try (var manager = new DesktopBackupManager(directory, clock, this::snapshot, this::validateFixture)) {
            manager.initialize(); assertNotNull(manager.status().warning());
            Files.delete(directory.resolve("daily")); changes.incrementAndGet(); manager.committed(); manager.flush();
            assertTrue(Files.isRegularFile(directory.resolve("daily/2026-10-19.json")));
            assertNull(manager.status().warning());
        }
    }
    @Test void 当今日备份缺少必需字段时应该保留损坏原件并拒绝当作成功备份() throws Exception {
        Path file = directory.resolve("daily/2026-10-19.json"); Files.createDirectories(file.getParent());
        var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(snapshot()); invalid.remove("exportedAt");
        String original = invalid.toString(); Files.writeString(file, original);
        try (var manager = new DesktopBackupManager(directory, clock, this::snapshot, this::validateFixture)) {
            manager.initialize(); assertNull(manager.status().lastSuccessfulDay());
            changes.incrementAndGet(); manager.committed(); manager.flush();
            assertNotNull(manager.status().warning()); assertNull(manager.status().lastSuccessfulDay());
            assertEquals(original, Files.readString(file));
        }
    }
    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-19T04:00:00Z");
        void day(int day) { now = LocalDate.of(2026, 10, day).atTime(12, 0).toInstant(ZoneOffset.ofHours(8)); }
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
