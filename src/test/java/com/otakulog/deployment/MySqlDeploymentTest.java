package com.otakulog.deployment;

import com.otakulog.OtakuLogApplication;
import com.otakulog.entity.Anime;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.dto.AnimeDTO;
import com.otakulog.service.AnimeService;
import com.otakulog.service.BackupService;
import com.otakulog.service.AnnualReportService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "OTAKULOG_MYSQL_TEST", matches = "true")
class MySqlDeploymentTest {

    private final String server = System.getenv().getOrDefault("OTAKULOG_MYSQL_SERVER", "127.0.0.1:3306");
    private final String user = System.getenv().getOrDefault("DB_USER", "root");
    private final String password = System.getenv().getOrDefault("DB_PASS", "123456");

    @Test
    void 当空库启动并重启应用时应该完成迁移并保留数据() throws Exception {
        withDatabase(database -> {
            try (ConfigurableApplicationContext application = startApplication(database)) {
                Anime anime = new Anime();
                anime.setName("持久化验收");
                anime.setCurrentEpisode(1);
                anime.setStatus(AnimeStatus.WATCHING);
                application.getBean(AnimeRepository.class).saveAndFlush(anime);
                execute(database, "INSERT INTO episode_record (anime_id, episode_number, watched_date) "
                        + "VALUES (1, 1, '2026-10-01')");
            }
            try (ConfigurableApplicationContext application = startApplication(database)) {
                assertEquals(AnimeStatus.WATCHING,
                        application.getBean(AnimeRepository.class).findById(1L).orElseThrow().getStatus());
                assertEquals("持久化验收", query(database, "SELECT name FROM anime WHERE id = 1"));
                assertEquals("2026-10-01", query(database, "SELECT watched_date FROM episode_record"));
                assertEquals("0", query(database, "SELECT COUNT(*) FROM flyway_schema_history WHERE NOT success"));
            }
        });
    }

    @Test
    void 当V5旧库升级时应该保留番剧标签分组和观看日期() throws Exception {
        withDatabase(database -> {
            flyway(database, "5").migrate();
            execute(database, "INSERT INTO anime (name, current_episode, status, tags, legacy) "
                    + "VALUES ('旧库验收', 1, 'WATCHING', '剧情, 日常,剧情', 0)");
            execute(database, "INSERT INTO anime_group (name) VALUES ('收藏')");
            execute(database, "INSERT INTO anime_group_relation (group_id, anime_id) VALUES (1, 1)");
            execute(database, "INSERT INTO episode_record (anime_id, episode_number, watched_date) "
                    + "VALUES (1, 1, '2025-12-31')");
            flyway(database, "latest").migrate();
            flyway(database, "latest").validate();
            assertEquals("旧库验收", query(database, "SELECT name FROM anime WHERE id = 1"));
            assertEquals("剧情,日常", query(database,
                    "SELECT GROUP_CONCAT(t.name ORDER BY t.id) FROM tag t JOIN anime_tag a ON a.tag_id = t.id WHERE a.anime_id = 1"));
            assertEquals("收藏", query(database,
                    "SELECT g.name FROM anime_group g JOIN anime_group_relation r ON r.group_id = g.id WHERE r.anime_id = 1"));
            assertEquals("2025-12-31", query(database, "SELECT watched_date FROM episode_record"));
            assertEquals("LEGACY", query(database, "SELECT record_source FROM episode_record"));
            execute(database, "INSERT INTO episode_record (anime_id, episode_number, watched_date, record_source) VALUES (1, 2, NULL, 'IMPORT')");
            assertEquals("1", query(database, "SELECT COUNT(*) FROM episode_record WHERE watched_date IS NULL AND record_source = 'IMPORT'"));
            assertEquals("0", query(database,
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'anime' AND column_name = 'tags'"));
        });
    }

    private ConfigurableApplicationContext startApplication(String database) {
        return startApplication(database, new String[0]);
    }

    private ConfigurableApplicationContext startApplication(String database, String... extra) {
        var args = new java.util.ArrayList<String>(java.util.List.of(
                "--server.port=0", "--server.address=127.0.0.1",
                "--spring.datasource.url=" + url(database),
                "--spring.datasource.username=" + user, "--spring.datasource.password=" + password,
                "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
                "--spring.jpa.hibernate.ddl-auto=validate", "--spring.flyway.enabled=true",
                "--spring.flyway.locations=classpath:db/migration,classpath:com/otakulog",
                "--spring.flyway.out-of-order=true"));
        args.addAll(java.util.List.of(extra));
        return SpringApplication.run(OtakuLogApplication.class, args.toArray(String[]::new));
    }

    @Test
    void 当MySQL从V10升级并恢复历史运行调用后重启时应该保留观点且不会恢复外发任务() throws Exception {
        withDatabase(database -> {
            flyway(database, "10").migrate();
            try (FakeAi fake = new FakeAi()) {
                String json, historyKey;
                try (var app = startApplication(database, fake.config())) {
                    var memory = tasteMemory(database, app, "画像重启验收");
                    var ai = app.getBean(com.otakulog.service.TasteAiService.class);
                    ai.extract(1L, memory.id(), aiRequest(0L, UUID.randomUUID().toString()));
                    var backup = app.getBean(BackupService.class);
                    var doc = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(backup.exportJson());
                    var history = ((com.fasterxml.jackson.databind.node.ObjectNode) doc.path("aiCalls").get(0)).deepCopy();
                    historyKey = UUID.randomUUID().toString(); history.put("requestKey", historyKey); history.put("status", "RUNNING"); history.putNull("completedAt");
                    ((com.fasterxml.jackson.databind.node.ArrayNode) doc.path("aiCalls")).add(history);
                    json = doc.toString();
                    assertEquals(true, backup.previewJson(json).get("valid"));
                    backup.importJson(json); backup.importJson(json);
                    assertEquals(1, fake.count.get());
                    assertEquals("1", query(database, "SELECT COUNT(*) FROM ai_call WHERE history_only=1"));
                    assertEquals("1", query(database, "SELECT COUNT(*) FROM ai_call WHERE history_only=0"));
                }
                try (var app = startApplication(database, fake.config())) {
                    var backup = app.getBean(BackupService.class); var ai = app.getBean(com.otakulog.service.TasteAiService.class);
                    assertTrue(ai.get(historyKey).message().contains("未知"));
                    backup.importJson(json); assertEquals(1, fake.count.get());
                    var memory = app.getBean(com.otakulog.repository.AnimeMemoryRepository.class).findAll().get(0);
                    assertTrue(ai.extract(1L, memory.getId(), aiRequest(0L, historyKey)).message().contains("未知"));
                    assertEquals(1, fake.count.get());
                    var changed = new com.otakulog.dto.TasteAiDTO.Request(0L, java.util.Map.of("content", "人物"), UUID.randomUUID().toString());
                    assertEquals(com.otakulog.enums.AiCallStatus.SUCCEEDED, ai.extract(1L, memory.getId(), changed).status());
                    assertEquals(2, fake.count.get());
                    app.getBean(AnimeService.class).deleteAnime(1L);
                    assertEquals("0", query(database, "SELECT COUNT(*) FROM memory_insight"));
                    assertEquals("3", query(database, "SELECT COUNT(*) FROM ai_call"));
                    backup.importJson(json);
                    Long restored = app.getBean(AnimeRepository.class).findAll().get(0).getId();
                    app.getBean(AnimeService.class).batchDelete(java.util.List.of(restored));
                    assertEquals("0", query(database, "SELECT COUNT(*) FROM memory_insight"));
                    assertEquals("3", query(database, "SELECT COUNT(*) FROM ai_call"));
                }
            }
        });
    }

    @Test
    void 当MySQL同请求键并发提炼时应该只外发一次并保留真实用量() throws Exception {
        withDatabase(database -> {
            try (FakeAi fake = new FakeAi(); var app = startApplication(database, fake.config())) {
                var memory = tasteMemory(database, app, "AI并发验收"); fake.block = true;
                var ai = app.getBean(com.otakulog.service.TasteAiService.class); var request = aiRequest(0L, UUID.randomUUID().toString());
                var executor = Executors.newFixedThreadPool(2);
                try {
                    var first = executor.submit(() -> ai.extract(1L, memory.id(), request));
                    assertTrue(fake.entered.await(10, TimeUnit.SECONDS));
                    var second = executor.submit(() -> ai.extract(1L, memory.id(), request));
                    assertEquals(com.otakulog.enums.AiCallStatus.RUNNING, second.get(15, TimeUnit.SECONDS).status());
                    fake.release.countDown();
                    assertEquals(com.otakulog.enums.AiCallStatus.SUCCEEDED, first.get(15, TimeUnit.SECONDS).status());
                    assertEquals(1, fake.count.get()); assertEquals("1", query(database, "SELECT COUNT(*) FROM ai_call"));
                    assertEquals("9", query(database, "SELECT prompt_tokens FROM ai_call"));
                } finally { fake.release.countDown(); executor.shutdownNow(); }
            }
        });
    }

    @Test
    void 当MySQL等待模型期间改动原文时应该保存迟到用量并将结果标为失效() throws Exception {
        withDatabase(database -> {
            try (FakeAi fake = new FakeAi(); var app = startApplication(database, fake.config())) {
                var memory = tasteMemory(database, app, "AI迟到验收"); fake.block = true;
                var executor = Executors.newSingleThreadExecutor();
                try {
                    var future = executor.submit(() -> app.getBean(com.otakulog.service.TasteAiService.class).extract(1L, memory.id(), aiRequest(0L, UUID.randomUUID().toString())));
                    assertTrue(fake.entered.await(10, TimeUnit.SECONDS));
                    app.getBean(com.otakulog.service.AnimeMemoryService.class).update(1L, memory.id(), new com.otakulog.dto.AnimeMemoryDTO.WriteRequest(null, "现在改了看法", null, null, null, com.otakulog.enums.MemoryContext.NOTE, null, 0L));
                    fake.release.countDown();
                    assertEquals(com.otakulog.enums.AiCallStatus.STALE, future.get(15, TimeUnit.SECONDS).status());
                    assertEquals("4", query(database, "SELECT completion_tokens FROM ai_call"));
                    assertEquals("0", query(database, "SELECT COUNT(*) FROM memory_insight"));
                } finally { fake.release.countDown(); executor.shutdownNow(); }
            }
        });
    }

    @Test
    void 当MySQL预算预留与恢复并发时应该无死锁并保留额度计数() throws Exception {
        withDatabase(database -> {
            try (FakeAi fake = new FakeAi(); var app = startApplication(database, fake.config())) {
                var memory = tasteMemory(database, app, "恢复并发验收"); var backup = app.getBean(BackupService.class);
                String json = backup.exportJson(); var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
                try {
                    var restore = executor.submit(() -> { start.await(); return backup.importJson(json); });
                    var extract = executor.submit(() -> { start.await(); return app.getBean(com.otakulog.service.TasteAiService.class).extract(1L, memory.id(), aiRequest(0L, UUID.randomUUID().toString())); });
                    start.countDown(); assertEquals(true, restore.get(20, TimeUnit.SECONDS).get("valid"));
                    assertEquals(com.otakulog.enums.AiCallStatus.SUCCEEDED, extract.get(20, TimeUnit.SECONDS).status());
                    assertEquals("1", query(database, "SELECT reserved_calls FROM ai_quota"));
                    backup.importJson("[]"); assertEquals("1", query(database, "SELECT reserved_calls FROM ai_quota"));
                } finally { start.countDown(); executor.shutdownNow(); }
            }
        });
    }

    @Test
    void 当MySQL恢复调用写入真实失败时应该回滚全部集合与额度() throws Exception {
        withDatabase(database -> {
            try (FakeAi fake = new FakeAi(); var app = startApplication(database, fake.config())) {
                var memory = tasteMemory(database, app, "恢复回滚验收");
                app.getBean(com.otakulog.service.TasteAiService.class).extract(1L, memory.id(), aiRequest(0L, UUID.randomUUID().toString()));
                execute(database, "UPDATE anime SET current_episode=1 WHERE id=1");
                execute(database, "INSERT INTO tag (name,created_at) VALUES ('虚构人物','2026-01-01 12:00:00')");
                execute(database, "INSERT INTO anime_tag (anime_id,tag_id) VALUES (1,1)");
                execute(database, "INSERT INTO anime_group (name,description,color,sort_order) VALUES ('虚构回滚分组','整批验收','#123456',1)");
                execute(database, "INSERT INTO anime_group_relation (anime_id,group_id) VALUES (1,1)");
                execute(database, "INSERT INTO episode_record (anime_id,episode_number,watched_date,record_source) VALUES (1,1,'2026-01-01','WATCHED')");
                var backup = app.getBean(BackupService.class); String json = backup.exportJson();
                execute(database, "DELETE FROM anime"); execute(database, "DELETE FROM ai_call");
                execute(database, "DELETE FROM tag"); execute(database, "DELETE FROM anime_group");
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var before = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(backup.exportJson()); before.remove("exportedAt");
                execute(database, "CREATE TRIGGER reject_call BEFORE INSERT ON ai_call FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='隔离验收失败'");
                assertThrows(RuntimeException.class, () -> backup.importJson(json));
                var after = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(backup.exportJson()); after.remove("exportedAt");
                assertEquals(before, after);
                assertEquals("0", query(database, "SELECT COUNT(*) FROM anime"));
                assertEquals("0", query(database, "SELECT COUNT(*) FROM anime_memory"));
                assertEquals("0", query(database, "SELECT COUNT(*) FROM memory_insight"));
                assertEquals("0", query(database, "SELECT COUNT(*) FROM ai_call"));
                for (String table : new String[]{"tag", "anime_group", "anime_tag", "anime_group_relation", "episode_record"})
                    assertEquals("0", query(database, "SELECT COUNT(*) FROM " + table), table);
                assertEquals("1", query(database, "SELECT reserved_calls FROM ai_quota"));
                assertEquals(1, fake.count.get());
            }
        });
    }

    private com.otakulog.dto.AnimeMemoryDTO.Entry tasteMemory(String database, ConfigurableApplicationContext app, String name) throws Exception {
        execute(database, "INSERT INTO anime (name,current_episode,total_episodes,status,legacy) VALUES ('" + name + "',0,12,'WATCHING',0)");
        return app.getBean(com.otakulog.service.AnimeMemoryService.class).create(1L, new com.otakulog.dto.AnimeMemoryDTO.WriteRequest(UUID.randomUUID().toString(), "人物关系细腻", null, null, null, com.otakulog.enums.MemoryContext.NOTE, null, null));
    }

    @Test
    void 当MySQL恢复锁前并发记看和编辑资料时应该保留最新进度逐集与资料() throws Exception {
        withDatabase(database -> {
            try (var app = startApplication(database)) {
                execute(database, "INSERT INTO anime (name,current_episode,total_episodes,status,remark,score,legacy) VALUES ('恢复锁间隙验收',1,12,'WATCHING','旧资料',5,0)");
                execute(database, "INSERT INTO episode_record (anime_id,episode_number,watched_date,record_source) VALUES (1,1,'2026-01-01','WATCHED')");
                var backup = app.getBean(BackupService.class);
                var doc = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(backup.exportJson());
                ((com.fasterxml.jackson.databind.node.ObjectNode) doc.path("anime").get(0).path("data")).put("currentEpisode", 2);
                String json = doc.toString();
                var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                var restoreThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
                var intercepted = new java.util.concurrent.atomic.AtomicBoolean();
                var repository = (org.springframework.aop.framework.Advised) app.getBean(AnimeRepository.class);
                // 仅暂停恢复线程第一次取得作品锁，其他窗口的正常操作可以提交。
                org.aopalliance.intercept.MethodInterceptor pause = invocation -> {
                    if (Thread.currentThread() == restoreThread.get() && invocation.getMethod().getName().equals("findByIdForUpdate")
                            && intercepted.compareAndSet(false, true)) {
                        entered.countDown();
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("恢复锁间隙等待超时");
                    }
                    return invocation.proceed();
                };
                repository.addAdvice(0, pause);
                var executor = Executors.newSingleThreadExecutor();
                try {
                    var future = executor.submit(() -> { restoreThread.set(Thread.currentThread()); return backup.importJson(json); });
                    assertTrue(entered.await(10, TimeUnit.SECONDS));
                    var service = app.getBean(AnimeService.class);
                    service.nextEpisode(1L); service.nextEpisode(1L);
                    var edit = new com.otakulog.dto.AnimeUpdateDTO(); edit.setName("恢复锁间隙验收");
                    edit.setSeason("2026秋"); edit.setTotalEpisodes(12); edit.setScore(9.0); edit.setRemark("新资料");
                    service.updateAnime(1L, edit);
                    release.countDown(); assertEquals(true, future.get(20, TimeUnit.SECONDS).get("valid"));
                    assertEquals("3", query(database, "SELECT current_episode FROM anime"));
                    assertEquals("3", query(database, "SELECT COUNT(*) FROM episode_record WHERE record_source='WATCHED'"));
                    assertEquals("新资料", query(database, "SELECT remark FROM anime"));
                    assertEquals(9.0, Double.parseDouble(query(database, "SELECT score FROM anime")));
                } finally { release.countDown(); executor.shutdownNow(); repository.removeAdvice(pause); }
            }
        });
    }

    private com.otakulog.dto.TasteAiDTO.Request aiRequest(Long version, String key) {
        return new com.otakulog.dto.TasteAiDTO.Request(version, null, key);
    }

    private static class FakeAi implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile boolean block;
        FakeAi() throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                count.incrementAndGet(); exchange.getRequestBody().readAllBytes(); entered.countDown();
                try { if (block && !release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("假服务等待超时"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                String response = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"insights\\\":[{\\\"quoteField\\\":\\\"CONTENT\\\",\\\"quote\\\":\\\"人物\\\",\\\"statement\\\":\\\"喜欢人物\\\",\\\"direction\\\":\\\"LIKE\\\",\\\"aspect\\\":\\\"CHARACTER\\\",\\\"factor\\\":\\\"人物\\\"}]}\"}}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4}}";
                byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start();
        }
        String[] config() {
            return new String[]{"--app.ai.enabled=true", "--app.ai.api-key=isolated-fake", "--app.ai.endpoint=http://127.0.0.1:" + server.getAddress().getPort() + "/", "--app.ai.response-timeout-ms=25000"};
        }
        @Override public void close() { release.countDown(); server.stop(0); }
    }

    @Test
    void 当MySQL记看与退集时应该同步日常入口并只使用明确日期() throws Exception {
        withDatabase(database -> {
            try (ConfigurableApplicationContext application = startApplication(database)) {
                var today = java.time.LocalDate.now();
                execute(database, "INSERT INTO anime (name,current_episode,total_episodes,status,broadcast_day,legacy) VALUES "
                        + "('日常入口验收',2,3,'WATCHING'," + today.getDayOfWeek().getValue() + ",0)");
                execute(database, "INSERT INTO episode_record (anime_id,episode_number,watched_date,record_source) VALUES "
                        + "(1,1,'" + today.minusDays(2) + "','IMPORT'),(1,2,'" + today + "','LEGACY')");
                var daily = application.getBean(com.otakulog.service.DailyWatchService.class);
                String before = query(database, "SELECT CONCAT(current_episode,'/',status,'/',COALESCE(updated_at,'')) FROM anime WHERE id=1");
                var read = daily.getDaily();
                assertEquals(1, read.ongoingCount()); assertNull(read.todayAiringCount());
                assertEquals("UNVERIFIED", read.airingStatus()); assertTrue(read.todayAiring().isEmpty());
                assertEquals(today.minusDays(2), read.continueWatching().get(0).lastWatchedDate());
                assertEquals(before, query(database, "SELECT CONCAT(current_episode,'/',status,'/',COALESCE(updated_at,'')) FROM anime WHERE id=1"));
                AnimeService service = application.getBean(AnimeService.class);
                service.nextEpisode(1L);
                assertEquals(0, daily.getDaily().ongoingCount());
                assertEquals("FINISHED", query(database, "SELECT status FROM anime WHERE id=1"));
                service.prevEpisode(1L);
                assertEquals(1, daily.getDaily().ongoingCount());
                assertEquals(today.minusDays(2), daily.getDaily().continueWatching().get(0).lastWatchedDate());
                assertEquals("2", query(database, "SELECT COUNT(*) FROM episode_record"));
            }
        });
    }

    @Test
    void 当MySQL读取跨年观看与历史来源时应该分开年度口径并排除未评分() throws Exception {
        withDatabase(database -> {
            try (ConfigurableApplicationContext application = startApplication(database)) {
                execute(database, "INSERT INTO anime (name,current_episode,total_episodes,status,end_date,score,legacy) VALUES "
                        + "('跨年完成',12,12,'FINISHED','2026-02-01',8,0),"
                        + "('尚未评分',12,12,'FINISHED','2026-02-02',0,0),"
                        + "('仍在追中',3,12,'WATCHING',NULL,9,0)");
                execute(database, "INSERT INTO episode_record (anime_id,episode_number,watched_date,record_source) VALUES "
                        + "(1,1,'2025-12-31','WATCHED'),(1,2,'2026-01-01','WATCHED'),"
                        + "(3,1,'2026-03-01','WATCHED'),(3,2,'2026-03-02','LEGACY'),(3,3,NULL,'IMPORT')");
                var report = application.getBean(AnnualReportService.class).getAnnualReport(2026);
                assertEquals(2, report.getTotalWatched()); assertEquals(2, report.getTotalEpisodes());
                assertEquals(2, report.getWatchedAnimeCount()); assertEquals(1, report.getLegacyDatedEpisodes());
                assertEquals(1, report.getUndatedEpisodeRecords()); assertEquals(22, report.getMissingEpisodeRecords());
                assertEquals(8.0, report.getAverageRating()); assertEquals(1, report.getRatedAnimeCount());
                assertEquals(8.0, report.getMonthlyStats().get(1).get("avgScore"));
                assertEquals(1, report.getMonthlyStats().get(1).get("ratedCount"));
                assertEquals(1L, report.getMonthlyStats().get(0).get("episodes"));
                assertEquals(0.8, report.getWatchingHours());
                var json = application.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).valueToTree(report);
                assertEquals(24, json.path("minutesPerEpisode").asInt());
                assertEquals(true, json.path("watchingHoursEstimated").asBoolean());
                assertEquals("5", query(database, "SELECT COUNT(*) FROM episode_record"));
                assertEquals("1", query(database, "SELECT COUNT(*) FROM episode_record WHERE record_source='LEGACY'"));
            }
        });
    }

    @Test
    void 当MySQL空库恢复完整备份时应该保留关联日期来源并支持重复恢复() throws Exception {
        withDatabase(database -> {
            try (ConfigurableApplicationContext application = startApplication(database)) {
                execute(database, "INSERT INTO anime (name,current_episode,total_episodes,status,remark,legacy,created_at,updated_at) "
                        + "VALUES ('备份往返验收',2,12,'WATCHING','保留备注',0,'2025-01-01 12:00:00','2025-02-01 12:00:00')");
                execute(database, "INSERT INTO tag (name,created_at) VALUES ('剧情','2025-01-01 12:00:00')");
                execute(database, "INSERT INTO anime_tag (anime_id,tag_id) VALUES (1,1)");
                execute(database, "INSERT INTO anime_group (name,description,color,sort_order,created_at,updated_at) VALUES ('收藏','分组说明','#123456',1,NULL,NULL)");
                execute(database, "INSERT INTO anime_group_relation (anime_id,group_id) VALUES (1,1)");
                execute(database, "INSERT INTO episode_record (anime_id,episode_number,watched_date,record_source) VALUES (1,1,'2026-10-02','WATCHED'),(1,2,NULL,'IMPORT')");
                BackupService backup = application.getBean(BackupService.class);
                String json = backup.exportJson();
                execute(database, "DELETE FROM anime"); execute(database, "DELETE FROM tag"); execute(database, "DELETE FROM anime_group");
                assertEquals(1, backup.importJson(json).get("created")); backup.importJson(json);
                assertEquals("1", query(database, "SELECT COUNT(*) FROM anime"));
                assertEquals("1", query(database, "SELECT COUNT(*) FROM anime_tag"));
                assertEquals("1", query(database, "SELECT COUNT(*) FROM anime_group_relation"));
                assertEquals("2", query(database, "SELECT COUNT(*) FROM episode_record"));
                assertEquals("2026-10-02", query(database, "SELECT watched_date FROM episode_record WHERE episode_number=1"));
                assertEquals("1", query(database, "SELECT COUNT(*) FROM episode_record WHERE watched_date IS NULL AND record_source='IMPORT'"));
                assertEquals("2025-02-01 12:00:00", query(database, "SELECT updated_at FROM anime"));
                assertEquals("0", query(database, "SELECT COUNT(*) FROM anime_group WHERE created_at IS NOT NULL OR updated_at IS NOT NULL"));
            }
        });
    }

    @Test
    void 当V9旧库升级记忆表时应该保留旧资料且不猜感想或日期() throws Exception {
        withDatabase(database -> {
            flyway(database, "9").migrate();
            execute(database, "INSERT INTO anime (name,remark,current_episode,total_episodes,status,legacy) VALUES ('旧番记忆验收','原有备注',1,12,'WATCHING',0)");
            execute(database, "INSERT INTO episode_record (anime_id,episode_number,watched_date,record_source) VALUES (1,1,NULL,'IMPORT')");
            flyway(database, "latest").migrate(); flyway(database, "latest").validate();
            assertEquals("原有备注", query(database, "SELECT remark FROM anime"));
            assertEquals("1", query(database, "SELECT COUNT(*) FROM episode_record WHERE watched_date IS NULL"));
            assertEquals("0", query(database, "SELECT COUNT(*) FROM anime_memory"));
            try (ConfigurableApplicationContext application = startApplication(database)) {
                assertEquals(0, application.getBean(com.otakulog.service.AnimeMemoryService.class).get(1L, 0, 20).totalEntries());
            }
        });
    }

    @Test
    void 当MySQL记忆重启和备份恢复时应该保留中文原文日期与审计时间() throws Exception {
        withDatabase(database -> {
            String backupJson;
            try (ConfigurableApplicationContext application = startApplication(database)) {
                execute(database, "INSERT INTO anime (name,current_episode,total_episodes,status,legacy) VALUES ('记忆持久化验收',0,12,'WATCHING',0)");
                var memory = application.getBean(com.otakulog.service.AnimeMemoryService.class);
                memory.create(1L, new com.otakulog.dto.AnimeMemoryDTO.WriteRequest(UUID.randomUUID().toString(), "澎湃\n还想再听音乐", "演出", "部分剧情", "电影", com.otakulog.enums.MemoryContext.INITIAL, null, null));
                backupJson = application.getBean(BackupService.class).exportJson();
                assertEquals("1", query(database, "SELECT COUNT(*) FROM anime_memory WHERE watched_date IS NULL"));
            }
            try (ConfigurableApplicationContext application = startApplication(database)) {
                var backup = application.getBean(BackupService.class);
                var before = new com.fasterxml.jackson.databind.ObjectMapper().readTree(backupJson).path("memories").get(0);
                assertEquals("澎湃\n还想再听音乐", application.getBean(com.otakulog.service.AnimeMemoryService.class).get(1L, 0, 20).entries().get(0).content());
                execute(database, "DELETE FROM anime");
                assertEquals("0", query(database, "SELECT COUNT(*) FROM anime_memory"));
                assertEquals(1, backup.importJson(backupJson).get("newMemories"));
                assertEquals(0, backup.importJson(backupJson).get("newMemories"));
                var after = new com.fasterxml.jackson.databind.ObjectMapper().readTree(backup.exportJson()).path("memories").get(0);
                ((com.fasterxml.jackson.databind.node.ObjectNode) before).remove("animeKey");
                ((com.fasterxml.jackson.databind.node.ObjectNode) after).remove("animeKey");
                assertEquals(before, after);
            }
        });
    }

    @Test
    void 当MySQL两个窗口编辑相同记忆版本时应该只允许一次成功() throws Exception {
        withDatabase(database -> {
            try (ConfigurableApplicationContext application = startApplication(database)) {
                execute(database, "INSERT INTO anime (name,current_episode,total_episodes,status,legacy) VALUES ('记忆并发验收',0,12,'WATCHING',0)");
                var memory = application.getBean(com.otakulog.service.AnimeMemoryService.class);
                var firstRecord = memory.create(1L, new com.otakulog.dto.AnimeMemoryDTO.WriteRequest(UUID.randomUUID().toString(), "初看", null, null, null, com.otakulog.enums.MemoryContext.INITIAL, null, null));
                var request = new com.otakulog.dto.AnimeMemoryDTO.WriteRequest(null, "现在的回望", null, null, null, com.otakulog.enums.MemoryContext.REFLECTION, null, firstRecord.version());
                var executor = Executors.newFixedThreadPool(2);
                var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
                try {
                    java.util.concurrent.Callable<Boolean> call = () -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("记忆并发启动超时");
                        try { memory.update(1L, firstRecord.id(), request); return true; }
                        catch (com.otakulog.common.ConflictException e) { return false; }
                    };
                    var first = executor.submit(call); var second = executor.submit(call);
                    assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
                    assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0));
                    assertEquals(1, memory.get(1L, 0, 20).entries().get(0).version());
                    assertEquals("现在的回望", memory.get(1L, 0, 20).entries().get(0).content());
                    assertEquals("0", query(database, "SELECT current_episode FROM anime"));
                } finally { start.countDown(); executor.shutdownNow(); }
            }
        });
    }

    @Test
    void 当MySQL删除单部或批量作品时应该只删除对应观影记忆() throws Exception {
        withDatabase(database -> {
            try (ConfigurableApplicationContext application = startApplication(database)) {
                execute(database, "INSERT INTO anime (name,current_episode,total_episodes,status,legacy) VALUES ('单删验收',0,12,'WATCHING',0),('批删验收',0,12,'WATCHING',0)");
                var memory = application.getBean(com.otakulog.service.AnimeMemoryService.class);
                for (long id = 1; id <= 2; id++) memory.create(id, new com.otakulog.dto.AnimeMemoryDTO.WriteRequest(UUID.randomUUID().toString(), "感想", null, null, null, com.otakulog.enums.MemoryContext.NOTE, null, null));
                var service = application.getBean(AnimeService.class);
                service.deleteAnime(1L); assertEquals("1", query(database, "SELECT COUNT(*) FROM anime_memory"));
                service.batchDelete(java.util.List.of(2L)); assertEquals("0", query(database, "SELECT COUNT(*) FROM anime_memory"));
            }
        });
    }

    private Flyway flyway(String database, String target) {
        return Flyway.configure().dataSource(url(database), user, password)
                .locations("classpath:db/migration", "classpath:com/otakulog")
                .outOfOrder(true).target(target).load();
    }

    @Test
    void 当MySQL并发核对同一快照时应该仅一次成功并保留统计备份语义() throws Exception {
        withDatabase(database -> {
            try (ConfigurableApplicationContext application = startApplication(database)) {
                execute(database, "INSERT INTO anime (name,current_episode,total_episodes,status,legacy) VALUES ('逐集核对验收',2,12,'WATCHING',0)");
                execute(database, "INSERT INTO episode_record (anime_id,episode_number,watched_date,record_source) VALUES (1,1,'2024-01-02','LEGACY')");
                var history = application.getBean(com.otakulog.service.EpisodeHistoryService.class);
                var report = application.getBean(AnnualReportService.class);
                assertEquals(0, report.getAnnualReport(2024).getTotalEpisodes());
                var expected = history.get(1L, 0, 20).entries().get(0);
                var request = new com.otakulog.dto.EpisodeHistoryDTO.UpdateRequest(java.time.LocalDate.of(2024, 1, 2), expected);
                var executor = Executors.newFixedThreadPool(2);
                var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
                try {
                    java.util.concurrent.Callable<Boolean> call = () -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("并发核对等待超时");
                        try { history.update(1L, 1, request); return true; }
                        catch (com.otakulog.common.ConflictException e) { return false; }
                    };
                    var first = executor.submit(call); var second = executor.submit(call);
                    if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("并发核对启动超时");
                    start.countDown();
                    assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0));
                } finally { start.countDown(); executor.shutdownNow(); }
                assertEquals(1, report.getAnnualReport(2024).getTotalEpisodes());
                assertEquals(0, report.getAnnualReport(2024).getLegacyDatedEpisodes());
                assertEquals("2", query(database, "SELECT current_episode FROM anime"));
                var confirmed = history.get(1L, 0, 20).entries().get(0);
                history.update(1L, 1, new com.otakulog.dto.EpisodeHistoryDTO.UpdateRequest(java.time.LocalDate.of(2024, 2, 3), confirmed));
                var missing = history.get(1L, 0, 20).entries().get(1);
                history.update(1L, 2, new com.otakulog.dto.EpisodeHistoryDTO.UpdateRequest(null, missing));
                var backup = application.getBean(BackupService.class);
                String json = backup.exportJson();
                execute(database, "DELETE FROM anime");
                backup.importJson(json);
                assertEquals("2024-02-03", query(database, "SELECT watched_date FROM episode_record WHERE episode_number=1"));
                assertEquals("2", query(database, "SELECT COUNT(*) FROM episode_record WHERE record_source='MANUAL'"));
                assertEquals("1", query(database, "SELECT COUNT(*) FROM episode_record WHERE watched_date IS NULL"));
                assertEquals(1, report.getAnnualReport(2024).getTotalEpisodes());
                assertEquals(1, report.getAnnualReport(2024).getUndatedEpisodeRecords());
                assertEquals(0, report.getAnnualReport(2024).getMissingEpisodeRecords());
                Long restoredId = application.getBean(AnimeRepository.class).findAll().get(0).getId();
                var restoredEntry = history.get(restoredId, 0, 20).entries().get(0);
                // H2 的古代日期 JDBC 转换与 MySQL 不同，存储下界在目标数据库验收。
                history.update(restoredId, 1, new com.otakulog.dto.EpisodeHistoryDTO.UpdateRequest(java.time.LocalDate.of(1000, 1, 1), restoredEntry));
                assertEquals(java.time.LocalDate.of(1000, 1, 1), history.get(restoredId, 0, 20).entries().get(0).watchedDate());
                assertEquals("1000-01-01", query(database, "SELECT watched_date FROM episode_record WHERE episode_number=1"));
            }
        });
    }

    @Test
    void 当已有库在V1建立基线时应该忽略V0并正常升级() throws Exception {
        withDatabase(database -> {
            try (Connection connection = DriverManager.getConnection(url(database), user, password)) {
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/migration/V0__initial_schema.sql"));
            }
            Flyway migration = flyway(database, "5");
            migration.baseline();
            execute(database, "INSERT INTO anime (name, current_episode) VALUES ('基线验收', 0)");
            migration.migrate();
            execute(database, "ALTER TABLE anime MODIFY status ENUM('WATCHING','FINISHED','PLANNING','DROPPED')");
            flyway(database, "latest").migrate();
            flyway(database, "latest").validate();
            assertEquals("基线验收", query(database, "SELECT name FROM anime WHERE id = 1"));
            assertEquals("0", query(database, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '0'"));
            try (ConfigurableApplicationContext ignored = startApplication(database)) {
                assertEquals("基线验收", query(database, "SELECT name FROM anime WHERE id = 1"));
            }
        });
    }

    private String url(String database) {
        return "jdbc:mysql://" + server + "/" + database
                + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    }

    @Test
    void 当旧库存在未知状态时应该停止升级并保留原值() throws Exception {
        withDatabase(database -> {
            flyway(database, "5").migrate();
            execute(database, "INSERT INTO anime (name, status) VALUES ('异常状态验收', 'UNKNOWN')");
            assertThrows(FlywayException.class, () -> flyway(database, "latest").migrate());
            assertEquals("UNKNOWN", query(database, "SELECT status FROM anime WHERE id = 1"));
            assertEquals("varchar", query(database,
                    "SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'anime' AND column_name = 'status'"));
        });
    }

    private void execute(String database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url(database), user, password);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Test
    void 当MySQL并发推进两集时应该逐次增加并保留两条记录() throws Exception {
        withDatabase(database -> {
            try (ConfigurableApplicationContext application = startApplication(database)) {
                AnimeService service = application.getBean(AnimeService.class);
                AnimeDTO dto = new AnimeDTO();
                dto.setName("并发验收");
                dto.setTotalEpisodes(3);
                dto.setSeason("2026秋");
                dto.setScore(0.0);
                Long id = service.addAnime(dto).getId();
                var executor = Executors.newFixedThreadPool(2);
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                try {
                    java.util.concurrent.Callable<Void> request = () -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("并发验收等待超时");
                        service.nextEpisode(id);
                        return null;
                    };
                    var first = executor.submit(request);
                    var second = executor.submit(request);
                    if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("并发验收启动超时");
                    start.countDown();
                    first.get(20, TimeUnit.SECONDS);
                    second.get(20, TimeUnit.SECONDS);
                    assertEquals("3", query(database, "SELECT current_episode FROM anime"));
                    assertEquals("FINISHED", query(database, "SELECT status FROM anime"));
                    assertEquals("3", query(database, "SELECT COUNT(*) FROM episode_record WHERE record_source = 'WATCHED'"));
                } finally {
                    start.countDown();
                    executor.shutdownNow();
                }
            }
        });
    }

    private String query(String database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url(database), user, password);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    @Test
    void 当MySQL升级并并发确认同一收藏预览后重启时应该保留外部原文且只写入一次() throws Exception {
        withDatabase(database -> {
            flyway(database, "12").migrate();
            var count = new java.util.concurrent.atomic.AtomicInteger();
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var rows = java.util.List.of(
                    java.util.Map.of("subject", java.util.Map.of("id", 913001, "name", "虚构完成作品", "eps", 3),
                            "type", 2, "ep_status", 0, "rate", 8, "tags", java.util.List.of("收藏标签"), "comment", "外部原文\n<script>保留</script>"),
                    java.util.Map.of("subject", java.util.Map.of("id", 913002, "name", "虚构搁置作品", "eps", 0),
                            "type", 4, "ep_status", 0, "rate", 0, "tags", java.util.List.of(), "comment", ""));
            byte[] response = mapper.writeValueAsBytes(java.util.Map.of("total", 2, "data", rows));
            server.createContext("/", exchange -> {
                count.incrementAndGet(); exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
            }); server.start();
            String config = "--otakulog.bangumi.base-url=http://127.0.0.1:" + server.getAddress().getPort();
            String json;
            com.otakulog.dto.BangumiImportDTO.ConfirmRequest request;
            try {
                try (var app = startApplication(database, config)) {
                    var service = app.getBean(com.otakulog.service.BangumiImportPreviewService.class);
                    var preview = service.preview("tester", "test");
                    request = new com.otakulog.dto.BangumiImportDTO.ConfirmRequest(preview.previewKey(), UUID.randomUUID().toString(), true, true);
                    assertEquals("0", query(database, "SELECT COUNT(*) FROM anime"));
                    var executor = Executors.newFixedThreadPool(2);
                    try {
                        var first = executor.submit(() -> service.confirm("tester", request));
                        var second = executor.submit(() -> service.confirm("tester", request));
                        assertEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
                    } finally { executor.shutdownNow(); }
                    assertEquals("1", query(database, "SELECT COUNT(*) FROM anime"));
                    assertEquals("2", query(database, "SELECT COUNT(*) FROM external_collection"));
                    assertEquals("1", query(database, "SELECT COUNT(*) FROM import_batch"));
                    assertEquals("3", query(database, "SELECT COUNT(*) FROM episode_record WHERE watched_date IS NULL AND record_source='IMPORT'"));
                    json = app.getBean(BackupService.class).exportJson();
                    assertEquals(true, app.getBean(BackupService.class).previewJson(json).get("valid"));
                }
                try (var app = startApplication(database, config)) {
                    assertEquals(1, app.getBean(com.otakulog.service.BangumiImportPreviewService.class).confirm("tester", request).created());
                    var before = mapper.readTree(json); var after = mapper.readTree(app.getBean(BackupService.class).exportJson());
                    assertEquals(before.path("externalCollections"), after.path("externalCollections"));
                    assertEquals(before.path("importBatches"), after.path("importBatches")); assertEquals(1, count.get());
                }
            } finally { server.stop(0); }
        });
    }

    @Test
    void 当MySQL空库恢复旧备份与确认导入并发时应该串行核对且不产生重复作品() throws Exception {
        withDatabase(database -> {
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            byte[] response = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(java.util.Map.of("total", 1, "data", java.util.List.of(
                    java.util.Map.of("subject", java.util.Map.of("id", 914001, "name", "虚构并发恢复", "eps", 12), "type", 1, "ep_status", 0))));
            server.createContext("/", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
            }); server.start();
            try (var app = startApplication(database, "--otakulog.bangumi.base-url=http://127.0.0.1:" + server.getAddress().getPort())) {
                var anime = app.getBean(AnimeRepository.class); var value = new Anime();
                value.setName("虚构并发恢复"); value.setBangumiId(914001); value.setTotalEpisodes(12); anime.saveAndFlush(value);
                var backup = app.getBean(BackupService.class);
                var legacy = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(backup.exportJson());
                legacy.put("version", 3); legacy.remove("externalCollections"); legacy.remove("importBatches"); legacy.remove("recommendationPreferences"); legacy.remove("recommendationFeedback"); legacy.remove("recommendationBases"); anime.deleteAll();
                var service = app.getBean(com.otakulog.service.BangumiImportPreviewService.class);
                var preview = service.preview("tester", "test");
                var request = new com.otakulog.dto.BangumiImportDTO.ConfirmRequest(preview.previewKey(), UUID.randomUUID().toString(), false, false);
                var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                var restoreThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
                var repository = (org.springframework.aop.framework.Advised) anime;
                org.aopalliance.intercept.MethodInterceptor pause = invocation -> {
                    var result = invocation.proceed();
                    // 强制空库恢复已读完身份后，另一线程才尝试确认导入。
                    if (Thread.currentThread() == restoreThread.get() && invocation.getMethod().getName().equals("findAllIdsForRestore")) {
                        entered.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("恢复等待超时");
                    }
                    return result;
                };
                repository.addAdvice(0, pause); var executor = Executors.newFixedThreadPool(2);
                try {
                    var restoring = executor.submit(() -> { restoreThread.set(Thread.currentThread()); return backup.importJson(legacy.toString()); });
                    assertTrue(entered.await(10, TimeUnit.SECONDS));
                    var confirming = executor.submit(() -> service.confirm("tester", request));
                    assertThrows(java.util.concurrent.TimeoutException.class, () -> confirming.get(400, TimeUnit.MILLISECONDS));
                    release.countDown(); assertEquals(true, restoring.get(20, TimeUnit.SECONDS).get("valid"));
                    var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> confirming.get(20, TimeUnit.SECONDS));
                    org.junit.jupiter.api.Assertions.assertInstanceOf(com.otakulog.common.ConflictException.class, failure.getCause());
                    assertEquals("1", query(database, "SELECT COUNT(*) FROM anime"));
                    assertEquals("0", query(database, "SELECT COUNT(*) FROM external_collection"));
                    assertEquals("0", query(database, "SELECT COUNT(*) FROM import_batch"));
                } finally { release.countDown(); executor.shutdownNow(); repository.removeAdvice(pause); }
            } finally { server.stop(0); }
        });
    }

    @Test
    void 当MySQL从V13升级并并发反馈后重启时应该保留条件与撤销历史且不写观看数据() throws Exception {
        withDatabase(database -> {
            flyway(database,"13").migrate();var count=new java.util.concurrent.atomic.AtomicInteger();
            var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            server.createContext("/",exchange -> {
                count.incrementAndGet();Object value;
                if(exchange.getRequestURI().getPath().equals("/v0/search/subjects"))value=java.util.Map.of("data",java.util.List.of(java.util.Map.of("id",951001,"name","虚构推荐一"),java.util.Map.of("id",951002,"name","虚构推荐二")));
                else {int id=Integer.parseInt(exchange.getRequestURI().getPath().substring("/v0/subjects/".length()));var tags=new java.util.ArrayList<Object>();tags.add(java.util.Map.of("name","剧情","count",1));
                    for(int i=0;i<10;i++)tags.add(java.util.Map.of("name","补充标签"+i,"count",1));if(id==951002)tags.add(java.util.Map.of("name","机战","count",1));
                    value=java.util.Map.of("id",id,"type",2,"name","虚构推荐"+id,"date","2020-01-01","tags",tags);}
                byte[] bytes=mapper.writeValueAsBytes(value);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();String config="--otakulog.bangumi.base-url=http://127.0.0.1:"+server.getAddress().getPort();
            com.otakulog.dto.RecommendationDTO.FeedbackRequest request;String exported;
            try {
                try(var app=startApplication(database,config)){
                    var state=app.getBean(com.otakulog.service.RecommendationState.class);var service=app.getBean(com.otakulog.service.RecommendationService.class);
                    state.save(new com.otakulog.dto.RecommendationDTO.Settings(null,0L,2020,2020,java.util.List.of("机战"),java.util.List.of("剧情"),false));
                    var batch=service.recommend("tester",false);assertEquals(1,batch.entries().size());assertEquals(951001,batch.entries().get(0).id());
                    request=new com.otakulog.dto.RecommendationDTO.FeedbackRequest(UUID.randomUUID().toString(),batch.batchKey(),951001,com.otakulog.dto.RecommendationDTO.Kind.NOT_INTERESTED);
                    var executor=Executors.newFixedThreadPool(2);
                    try{var first=executor.submit(() -> service.feedback("tester",request));var second=executor.submit(() -> service.feedback("tester",request));assertEquals(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));}finally{executor.shutdownNow();}
                    state.remove(request.requestKey(),0);service.shown("tester",new com.otakulog.dto.RecommendationDTO.ShownRequest(batch.batchKey(),java.util.List.of(951001)));
                    exported=app.getBean(BackupService.class).exportJson();assertEquals(true,app.getBean(BackupService.class).previewJson(exported).get("valid"));
                    assertEquals("1",query(database,"SELECT COUNT(*) FROM recommendation_feedback"));assertEquals("0",query(database,"SELECT COUNT(*) FROM anime"));assertEquals("0",query(database,"SELECT COUNT(*) FROM episode_record"));
                    assertEquals("1",query(database,"SELECT COUNT(*) FROM recommendation_shown"));
                }
                int before=count.get();
                try(var app=startApplication(database,config)){
                    var state=app.getBean(com.otakulog.service.RecommendationState.class);assertEquals(2020,state.settings().minYear());
                    assertEquals(false,app.getBean(com.otakulog.service.RecommendationService.class).feedback("tester",request).active());
                    var backup=app.getBean(BackupService.class);backup.importJson(exported);
                    var expected=mapper.readTree(exported);var actual=mapper.readTree(backup.exportJson());assertEquals(expected.path("recommendationPreferences"),actual.path("recommendationPreferences"));assertEquals(expected.path("recommendationFeedback"),actual.path("recommendationFeedback"));assertEquals(before,count.get());
                }
            }finally{server.stop(0);}
        });
    }

    @Test
    void 当MySQL从V14升级并并发保存关联后重启时应该保留来源核对与暂停状态() throws Exception {
        withDatabase(database -> {
            flyway(database,"14").migrate();
            var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);var calls=new java.util.concurrent.atomic.AtomicInteger();
            server.createContext("/",exchange -> {calls.incrementAndGet();byte[] bytes="{\"id\":971001,\"type\":2,\"name\":\"虚构种子\",\"tags\":[{\"name\":\"剧情\",\"count\":1}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
            String config="--otakulog.bangumi.base-url=http://127.0.0.1:"+server.getAddress().getPort();String exported;com.otakulog.dto.RecommendationBasisDTO.Write request;
            try {
                try(var app=startApplication(database,config)){
                    var a=new Anime();a.setName("虚构关联来源");a.setTotalEpisodes(12);a.setCurrentEpisode(0);a.setStatus(AnimeStatus.PLANNING);a=app.getBean(AnimeRepository.class).saveAndFlush(a);
                    var memory=app.getBean(com.otakulog.service.AnimeMemoryService.class).create(a.getId(),new com.otakulog.dto.AnimeMemoryDTO.WriteRequest(UUID.randomUUID().toString(),"虚构原文：人物选择让我喜欢",null,null,null,com.otakulog.enums.MemoryContext.NOTE,null,null));
                    var insight=app.getBean(com.otakulog.service.MemoryInsightService.class).create(a.getId(),memory.id(),new com.otakulog.dto.MemoryInsightDTO.WriteRequest(UUID.randomUUID().toString(),0L,com.otakulog.enums.InsightQuoteField.CONTENT,"人物选择","虚构已核对观点",com.otakulog.enums.InsightDirection.LIKE,com.otakulog.enums.InsightAspect.CHARACTER,"人物选择",com.otakulog.enums.InsightStatus.CONFIRMED,null));
                    var service=app.getBean(com.otakulog.service.RecommendationBasisService.class);var source=service.get().sources().get(0);
                    request=new com.otakulog.dto.RecommendationBasisDTO.Write(UUID.randomUUID().toString(),insight.key(),0L,0L,source.sourceFingerprint(),com.otakulog.enums.InsightDirection.LIKE,com.otakulog.dto.RecommendationBasisDTO.Kind.SEED,null,971001,true,0L);
                    service.preview(request);assertEquals("0",query(database,"SELECT COUNT(*) FROM recommendation_basis"));
                    var executor=Executors.newFixedThreadPool(2);try{var one=executor.submit(() -> service.save(request));var two=executor.submit(() -> service.save(request));assertEquals(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));}finally{executor.shutdownNow();}
                    assertEquals("1",query(database,"SELECT COUNT(*) FROM recommendation_basis"));assertEquals("0",query(database,"SELECT COUNT(*) FROM episode_record"));
                    execute(database,"UPDATE anime_memory SET version=1,content='虚构改变后的原文' WHERE id="+memory.id());assertEquals("STALE",service.get().entries().get(0).state());
                    exported=app.getBean(BackupService.class).exportJson();
                }
                int before=calls.get();try(var app=startApplication(database,config)){
                    var service=app.getBean(com.otakulog.service.RecommendationBasisService.class);assertEquals("STALE",service.save(request).state());
                    var backup=app.getBean(BackupService.class);backup.importJson(exported);var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
                    assertEquals(mapper.readTree(exported).path("recommendationBases"),mapper.readTree(backup.exportJson()).path("recommendationBases"));assertEquals(before,calls.get());
                    assertEquals("0",query(database,"SELECT COUNT(*) FROM episode_record"));assertEquals("0",query(database,"SELECT current_episode FROM anime"));
                }
            }finally{server.stop(0);}
        });
    }

    private void withDatabase(DatabaseCheck check) throws Exception {
        // 库名完全由测试生成，清理只针对本次成功创建的隔离库。
        String database = "otakulog_verify_" + UUID.randomUUID().toString().replace("-", "");
        execute("", "CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        try {
            check.run(database);
        } finally {
            execute("", "DROP DATABASE `" + database + "`");
        }
    }

    @FunctionalInterface
    private interface DatabaseCheck {
        void run(String database) throws Exception;
    }
}
