package com.otakulog.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otakulog.OtakuLogApplication;
import com.otakulog.common.ConflictException;
import com.otakulog.dto.AnimeDTO;
import com.otakulog.dto.AnimeMemoryDTO;
import com.otakulog.dto.BangumiImportDTO;
import com.otakulog.dto.EpisodeHistoryDTO;
import com.otakulog.dto.RecommendationDTO;
import com.otakulog.dto.TasteAiDTO;
import com.otakulog.enums.AiCallStatus;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.enums.EpisodeRecordSource;
import com.otakulog.enums.MemoryContext;
import com.otakulog.repository.AiQuotaRepository;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.EpisodeRecordRepository;
import com.otakulog.repository.ImportLockRepository;
import com.otakulog.service.AnimeMemoryService;
import com.otakulog.service.AnimeService;
import com.otakulog.service.BackupService;
import com.otakulog.service.BangumiImportPreviewService;
import com.otakulog.service.EpisodeHistoryService;
import com.otakulog.service.RecommendationService;
import com.otakulog.service.RecommendationState;
import com.otakulog.service.TasteAiService;
import com.sun.net.httpserver.HttpServer;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.Advised;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DesktopWriteConsistencyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void 当文件库两个窗口同时进集再退回计划时应该逐次记看并同步删除记录() throws Exception {
        try (var app = startApplication(temporaryDirectory.resolve("并发记看资料"))) {
            var service = app.getBean(AnimeService.class);
            var records = app.getBean(EpisodeRecordRepository.class);
            Long id = service.addAnime(anime("虚构并发记看", "planning")).getId();
            together(() -> service.nextEpisode(id), () -> service.nextEpisode(id));
            assertEquals(2, app.getBean(AnimeRepository.class).findById(id).orElseThrow().getCurrentEpisode());
            assertEquals(List.of(1, 2), records.findByAnimeIdOrderByEpisodeNumberAsc(id).stream()
                    .map(record -> record.getEpisodeNumber()).toList());
            assertTrue(records.findAll().stream().allMatch(record -> LocalDate.now().equals(record.getWatchedDate())
                    && record.getSource() == EpisodeRecordSource.WATCHED));
            assertEquals("finished", service.nextEpisode(id).getStatus());
            var reduced = service.prevEpisode(id);
            assertEquals("watching", reduced.getStatus());
            assertNull(reduced.getEndDate());
            assertFalse(records.findByAnimeIdAndEpisodeNumber(id, 3).isPresent());
            service.prevEpisode(id);
            var empty = service.prevEpisode(id);
            assertEquals("planning", empty.getStatus());
            assertEquals(0, empty.getCurrentEpisode());
            assertEquals(0, records.count());
            assertThrows(IllegalArgumentException.class, () -> service.prevEpisode(id));
        }
    }

    @Test
    void 当完成记录日期未知且两窗口核对同一快照时应该只保存一次并保留微秒精度() throws Exception {
        try (var app = startApplication(temporaryDirectory.resolve("逐集核对资料"))) {
            var service = app.getBean(AnimeService.class);
            Long id = service.addAnime(anime("虚构未知日期", "planning")).getId();
            service.updateStatus(id, AnimeStatus.FINISHED);
            var records = app.getBean(EpisodeRecordRepository.class);
            assertEquals(3, records.count());
            assertTrue(records.findAll().stream().allMatch(record -> record.getWatchedDate() == null
                    && record.getSource() == EpisodeRecordSource.MANUAL));
            assertTrue(service.getHeatmap().values().stream().allMatch(value -> value == 0));

            var history = app.getBean(EpisodeHistoryService.class);
            var audit = LocalDateTime.of(2024, 1, 2, 3, 4, 5, 123456000);
            jdbc(app).update("UPDATE episode_record SET updated_at=? WHERE anime_id=? AND episode_number=1", audit, id);
            var snapshot = history.get(id, 0, 20).entries().get(0);
            assertEquals(audit, snapshot.updatedAt());
            var firstDate = LocalDate.of(2024, 2, 3);
            var secondDate = LocalDate.of(2024, 3, 4);
            var results = together(
                    () -> correct(history, id, snapshot, firstDate),
                    () -> correct(history, id, snapshot, secondDate));
            assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
            var saved = history.get(id, 0, 20).entries().get(0);
            assertTrue(List.of(firstDate, secondDate).contains(saved.watchedDate()));
            assertEquals(0, saved.updatedAt().getNano() % 1000);
            assertEquals(3, records.count());
            assertEquals(3, app.getBean(AnimeRepository.class).findById(id).orElseThrow().getCurrentEpisode());
            assertEquals(2, records.findAll().stream().filter(record -> record.getWatchedDate() == null).count());
        }
    }

    @Test
    void 当长篇记忆以同一提交键重试并重启时应该保留原文未知日期与审计且拒绝不同内容() throws Exception {
        Path directory = temporaryDirectory.resolve("长篇记忆 资料");
        var request = memory(UUID.randomUUID().toString(), "虚构中文长感想\n".repeat(2000), null);
        AnimeMemoryDTO.Entry saved;
        Long id;
        try (var app = startApplication(directory)) {
            id = app.getBean(AnimeService.class).addAnime(anime("虚构长篇记忆", "planning")).getId();
            var service = app.getBean(AnimeMemoryService.class);
            saved = service.create(id, request);
            assertEquals(16000, saved.content().length());
            assertEquals(saved, service.create(id, request));
            assertNull(saved.watchedDate());
            assertEquals(0, saved.createdAt().getNano() % 1000);
            assertEquals(0, saved.updatedAt().getNano() % 1000);
            Long animeId = id;
            assertThrows(ConflictException.class, () -> service.create(animeId, memory(request.key(), "虚构其他内容", null)));
            assertEquals(1, service.get(id, 0, 20).totalEntries());
        }
        try (var app = startApplication(directory)) {
            var service = app.getBean(AnimeMemoryService.class);
            assertEquals(saved, service.create(id, request));
            assertEquals(0, app.getBean(EpisodeRecordRepository.class).count());
            assertEquals(0, app.getBean(AnimeRepository.class).findById(id).orElseThrow().getCurrentEpisode());
        }
    }

    @Test
    void 当文件库保存日期下界并备份重启时应该保留微秒审计且拒绝越界修改() throws Exception {
        Path restoredDirectory = temporaryDirectory.resolve("日期下界 恢复资料");
        var lowerDate = LocalDate.of(1000, 1, 1);
        var createdAt = LocalDateTime.of(2024, 1, 2, 3, 4, 5, 123456000);
        var updatedAt = createdAt.plusSeconds(1).withNano(654321000);
        var mapper = new ObjectMapper();
        String exported;
        AnimeMemoryDTO.Entry saved;
        try (var app = startApplication(temporaryDirectory.resolve("日期下界 原始资料"))) {
            Long id = app.getBean(AnimeService.class).addAnime(anime("虚构日期下界", "planning")).getId();
            var service = app.getBean(AnimeMemoryService.class);
            var first = service.create(id, memory(UUID.randomUUID().toString(), "虚构边界日期核对", null));
            service.update(id, first.id(), new AnimeMemoryDTO.WriteRequest(null, first.content(), null, null, null,
                    MemoryContext.NOTE, lowerDate, first.version()));
            jdbc(app).update("UPDATE anime_memory SET created_at=?,updated_at=? WHERE id=?", createdAt, updatedAt, first.id());
            saved = service.getEntry(id, first.id());
            assertEquals(lowerDate, saved.watchedDate());
            assertEquals("1000-01-01", jdbc(app).queryForObject(
                    "SELECT CAST(watched_date AS VARCHAR) FROM anime_memory WHERE id=?", String.class, first.id()));
            assertEquals(createdAt, saved.createdAt());
            assertEquals(updatedAt, saved.updatedAt());

            var backup = app.getBean(BackupService.class);
            exported = backup.exportJson();
            var before = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(exported);
            before.remove("exportedAt");
            assertEquals("1000-01-01", before.path("memories").get(0).path("watchedDate").asText());
            assertThrows(IllegalArgumentException.class, () -> service.update(id, first.id(),
                    new AnimeMemoryDTO.WriteRequest(null, "不能残留的越界修改", null, null, null,
                            MemoryContext.NOTE, LocalDate.of(999, 12, 31), saved.version())));
            assertEquals(saved, service.getEntry(id, first.id()));
            var after = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(backup.exportJson());
            after.remove("exportedAt");
            assertEquals(before, after);
        }

        Long restoredAnimeId;
        Long restoredMemoryId;
        try (var app = startApplication(restoredDirectory)) {
            var backup = app.getBean(BackupService.class);
            assertEquals(true, backup.previewJson(exported).get("valid"));
            assertEquals(true, backup.importJson(exported).get("valid"));
            restoredAnimeId = app.getBean(AnimeRepository.class).findAll().get(0).getId();
            var restored = app.getBean(AnimeMemoryService.class).get(restoredAnimeId, 0, 20).entries().get(0);
            restoredMemoryId = restored.id();
            assertEquals(saved.key(), restored.key());
            assertEquals(lowerDate, restored.watchedDate());
            assertEquals("1000-01-01", jdbc(app).queryForObject(
                    "SELECT CAST(watched_date AS VARCHAR) FROM anime_memory WHERE id=?", String.class, restored.id()));
            assertEquals(createdAt, restored.createdAt());
            assertEquals(updatedAt, restored.updatedAt());
        }
        try (var app = startApplication(restoredDirectory)) {
            var restored = app.getBean(AnimeMemoryService.class).getEntry(restoredAnimeId, restoredMemoryId);
            assertEquals(lowerDate, restored.watchedDate());
            assertEquals(createdAt, restored.createdAt());
            assertEquals(updatedAt, restored.updatedAt());
            var expected = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(exported).path("memories").get(0);
            var actual = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(app.getBean(BackupService.class).exportJson()).path("memories").get(0);
            expected.remove("animeKey"); actual.remove("animeKey");
            assertEquals(expected, actual);
        }
    }

    @Test
    void 当文件库两个窗口编辑相同记忆版本时应该只允许一次成功且旧版本不能删除() throws Exception {
        try (var app = startApplication(temporaryDirectory.resolve("记忆版本资料"))) {
            Long id = app.getBean(AnimeService.class).addAnime(anime("虚构版本冲突", "planning")).getId();
            var service = app.getBean(AnimeMemoryService.class);
            var before = service.create(id, memory(UUID.randomUUID().toString(), "虚构初看原文", null));
            var request = memory(null, "虚构回望原文", before.version());
            Callable<Boolean> update = () -> {
                try { service.update(id, before.id(), request); return true; }
                catch (ConflictException expected) { return false; }
            };
            assertEquals(1, together(update, update).stream().filter(Boolean::booleanValue).count());
            var after = service.getEntry(id, before.id());
            assertEquals(1L, after.version());
            assertEquals("虚构回望原文", after.content());
            assertThrows(ConflictException.class, () -> service.delete(id, before.id(), before.version()));
            assertEquals(after, service.getEntry(id, before.id()));
        }
    }

    @Test
    void 当推荐反馈并发重试后撤销并重启时应该保留撤销历史且不写观看数据() throws Exception {
        Path directory = temporaryDirectory.resolve("推荐反馈资料");
        RecommendationDTO.FeedbackRequest request;
        RecommendationDTO.FeedbackEntry removed;
        try (var upstream = new FictionalUpstream()) {
            try (var app = startApplication(directory, upstream.bangumiConfiguration())) {
                var state = app.getBean(RecommendationState.class);
                state.save(new RecommendationDTO.Settings(null, 0L, 2020, 2020, List.of("机战"), List.of("剧情"), false));
                var service = app.getBean(RecommendationService.class);
                var batch = service.recommend("desktop-test", false);
                assertEquals(List.of(981001), batch.entries().stream().map(RecommendationDTO.Candidate::id).toList());
                request = new RecommendationDTO.FeedbackRequest(UUID.randomUUID().toString(), batch.batchKey(), 981001,
                        RecommendationDTO.Kind.NOT_INTERESTED);
                var results = together(() -> service.feedback("desktop-test", request), () -> service.feedback("desktop-test", request));
                assertEquals(results.get(0), results.get(1));
                state.remove(request.requestKey(), results.get(0).version());
                removed = service.feedback("desktop-test", request);
                assertFalse(removed.active());
                assertEquals(1L, removed.version());
                assertEquals(1, jdbc(app).queryForObject("SELECT COUNT(*) FROM recommendation_feedback", Integer.class));
            }
            int requestsBeforeRestart = upstream.calls.get();
            try (var app = startApplication(directory, upstream.bangumiConfiguration())) {
                assertEquals(removed, app.getBean(RecommendationService.class).feedback("desktop-test", request));
                assertEquals(requestsBeforeRestart, upstream.calls.get());
                assertEquals(2020, app.getBean(RecommendationState.class).settings().minYear());
                assertEquals(0, app.getBean(AnimeRepository.class).count());
                assertEquals(0, app.getBean(EpisodeRecordRepository.class).count());
            }
        }
    }

    @Test
    void 当同一收藏预览并发确认并重启重试时应该只导入一次且保留外部原文与未知日期() throws Exception {
        Path directory = temporaryDirectory.resolve("收藏导入资料");
        BangumiImportDTO.ConfirmRequest request;
        BangumiImportDTO.Result saved;
        try (var upstream = new FictionalUpstream()) {
            try (var app = startApplication(directory, upstream.bangumiConfiguration())) {
                var service = app.getBean(BangumiImportPreviewService.class);
                var preview = service.preview("desktop-test", "fictional-user");
                assertEquals(0, app.getBean(AnimeRepository.class).count());
                request = new BangumiImportDTO.ConfirmRequest(preview.previewKey(), UUID.randomUUID().toString(), true, true);
                var results = confirmTogetherAfterWaitingForImportLock(app, service, request);
                saved = results.get(0);
                assertEquals(saved, results.get(1));
                assertEquals(1, saved.created());
                assertEquals(1, jdbc(app).queryForObject("SELECT COUNT(*) FROM import_batch", Integer.class));
                assertEquals(1, jdbc(app).queryForObject("SELECT COUNT(*) FROM external_collection", Integer.class));
                assertEquals(3, jdbc(app).queryForObject("SELECT COUNT(*) FROM episode_record WHERE watched_date IS NULL AND record_source='IMPORT'", Integer.class));
                assertEquals(0, jdbc(app).queryForObject("SELECT COUNT(*) FROM anime_memory", Integer.class));
                assertTrue(jdbc(app).queryForObject("SELECT payload FROM external_collection", String.class).contains(FictionalUpstream.COMMENT));
                assertTrue(app.getBean(AnimeService.class).getHeatmap().values().stream().allMatch(value -> value == 0));
            }
            int requestsBeforeRestart = upstream.calls.get();
            try (var app = startApplication(directory, upstream.bangumiConfiguration())) {
                assertEquals(saved, app.getBean(BangumiImportPreviewService.class).confirm("desktop-test", request));
                assertEquals(requestsBeforeRestart, upstream.calls.get());
                assertEquals(1, app.getBean(AnimeRepository.class).count());
            }
        }
    }

    @Test
    void 当模型等待期间同键重试并恢复空备份时应该只外发一次且保留额度和真实用量() throws Exception {
        try (var upstream = new FictionalUpstream();
             var app = startApplication(temporaryDirectory.resolve("模型并发资料"), upstream.aiConfiguration())) {
            Long id = app.getBean(AnimeService.class).addAnime(anime("虚构模型防重", "planning")).getId();
            var memory = app.getBean(AnimeMemoryService.class).create(id, memory(UUID.randomUUID().toString(), "虚构人物关系细腻", null));
            var service = app.getBean(TasteAiService.class);
            var request = new TasteAiDTO.Request(memory.version(), null, UUID.randomUUID().toString());
            var executor = Executors.newFixedThreadPool(2);
            try {
                var first = executor.submit(() -> service.extract(id, memory.id(), request));
                assertTrue(upstream.aiEntered.await(10, TimeUnit.SECONDS));
                var second = executor.submit(() -> service.extract(id, memory.id(), request));
                assertEquals(AiCallStatus.RUNNING, second.get(15, TimeUnit.SECONDS).status());
                // 模型响应未返回时恢复仍能完成，证明 HTTP 等待没有占用额度或作品事务锁。
                var restoring = executor.submit(() -> app.getBean(BackupService.class).importJson("[]"));
                assertEquals(true, restoring.get(15, TimeUnit.SECONDS).get("valid"));
                upstream.aiRelease.countDown();
                var result = first.get(20, TimeUnit.SECONDS);
                assertEquals(AiCallStatus.SUCCEEDED, result.status());
                assertEquals(9, result.usage().promptTokens());
                assertEquals(4, result.usage().completionTokens());
                assertEquals(1, upstream.calls.get());
                assertEquals(1, jdbc(app).queryForObject("SELECT COUNT(*) FROM ai_call", Integer.class));
                assertEquals(1, jdbc(app).queryForObject("SELECT reserved_calls FROM ai_quota WHERE id=1", Integer.class));
                assertEquals(AiCallStatus.SUCCEEDED, service.extract(id, memory.id(), request).status());
                assertEquals(1, upstream.calls.get());
            } finally {
                upstream.aiRelease.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void 当恢复等待额度或导入锁时应该不提前锁住下游资源且完成后保留进度() throws Exception {
        try (var app = startApplication(temporaryDirectory.resolve("恢复锁顺序资料"))) {
            var service = app.getBean(AnimeService.class);
            Long id = service.addAnime(anime("虚构锁顺序", "watching")).getId();
            assertRestoreWaitsBeforeDownstream(app, app.getBean(AiQuotaRepository.class), "findSingletonForUpdate",
                    "SELECT id FROM ai_quota WHERE id=1 FOR UPDATE",
                    List.of("SELECT id FROM import_lock WHERE id=1 FOR UPDATE", "SELECT id FROM anime WHERE id=" + id + " FOR UPDATE"));
            assertRestoreWaitsBeforeDownstream(app, app.getBean(ImportLockRepository.class), "lockSingleton",
                    "SELECT id FROM import_lock WHERE id=1 FOR UPDATE", List.of("SELECT id FROM anime WHERE id=" + id + " FOR UPDATE"));
            assertEquals(1, app.getBean(AnimeRepository.class).findById(id).orElseThrow().getCurrentEpisode());
            assertEquals(1, app.getBean(EpisodeRecordRepository.class).count());
        }
    }

    @Test
    void 当文件库遇到重复逐集或孤立记录时应该拒绝且删除作品时级联清理() throws Exception {
        try (var app = startApplication(temporaryDirectory.resolve("数据库约束资料"))) {
            var service = app.getBean(AnimeService.class);
            Long id = service.addAnime(anime("虚构约束验收", "watching")).getId();
            app.getBean(AnimeMemoryService.class).create(id, memory(UUID.randomUUID().toString(), "虚构级联记忆", null));
            var database = jdbc(app);
            assertThrows(DataIntegrityViolationException.class, () -> database.update(
                    "INSERT INTO episode_record(anime_id,episode_number,record_source) VALUES (?,1,'MANUAL')", id));
            assertThrows(DataIntegrityViolationException.class, () -> database.update(
                    "INSERT INTO episode_record(anime_id,episode_number,record_source) VALUES (?,1,'MANUAL')", Long.MAX_VALUE));
            assertEquals(1, app.getBean(EpisodeRecordRepository.class).count());
            service.deleteAnime(id);
            assertEquals(0, app.getBean(AnimeRepository.class).count());
            assertEquals(0, app.getBean(EpisodeRecordRepository.class).count());
            assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM anime_memory", Integer.class));
        }
    }

    private void assertRestoreWaitsBeforeDownstream(ConfigurableApplicationContext app, Object repository,
            String method, String heldLock, List<String> downstreamLocks) throws Exception {
        var entered = new CountDownLatch(1);
        var advised = (Advised) repository;
        MethodInterceptor signal = invocation -> {
            if (invocation.getMethod().getName().equals(method)) entered.countDown();
            return invocation.proceed();
        };
        advised.addAdvice(0, signal);
        var executor = Executors.newSingleThreadExecutor();
        try (var connection = app.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);
            try {
                lock(connection, heldLock);
                var restoring = executor.submit(() -> app.getBean(BackupService.class).importJson("[]"));
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> restoring.get(250, TimeUnit.MILLISECONDS));
                // 上游锁仍被另一事务占用，下游锁应可用；倒序拿锁会在此形成真实等待。
                for (String sql : downstreamLocks) lock(connection, sql);
                connection.commit();
                assertEquals(true, restoring.get(20, TimeUnit.SECONDS).get("valid"));
            } finally {
                connection.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            advised.removeAdvice(signal);
        }
    }

    private List<BangumiImportDTO.Result> confirmTogetherAfterWaitingForImportLock(ConfigurableApplicationContext app,
            BangumiImportPreviewService service, BangumiImportDTO.ConfirmRequest request) throws Exception {
        var entered = new CountDownLatch(2);
        var advised = (Advised) app.getBean(ImportLockRepository.class);
        MethodInterceptor signal = invocation -> {
            if (invocation.getMethod().getName().equals("lockSingleton")) entered.countDown();
            return invocation.proceed();
        };
        advised.addAdvice(0, signal);
        var executor = Executors.newFixedThreadPool(2);
        try (var connection = app.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);
            try {
                // 两个确认事务都在首笔写入前等待固定锁，稳定重现等待后的可见性边界。
                lock(connection, "SELECT id FROM import_lock WHERE id=1 FOR UPDATE");
                var first = executor.submit(() -> service.confirm("desktop-test", request));
                var second = executor.submit(() -> service.confirm("desktop-test", request));
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> first.get(250, TimeUnit.MILLISECONDS));
                assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS));
                connection.commit();
                return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            } finally {
                connection.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            advised.removeAdvice(signal);
        }
    }

    private void lock(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(3);
            try (var result = statement.executeQuery(sql)) {
                assertTrue(result.next());
            }
        }
    }

    private boolean correct(EpisodeHistoryService history, Long id, EpisodeHistoryDTO.Entry snapshot, LocalDate date) {
        try { history.update(id, 1, new EpisodeHistoryDTO.UpdateRequest(date, snapshot)); return true; }
        catch (ConflictException expected) { return false; }
    }

    private <T> List<T> together(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return first.call(); });
            var two = executor.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return second.call(); });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private ConfigurableApplicationContext startApplication(Path directory, String... extra) {
        var arguments = new ArrayList<>(List.of("--spring.profiles.active=desktop", "--spring.main.banner-mode=off",
                "--server.port=0", "--server.address=127.0.0.1", "--app.desktop.data-dir=" + directory));
        arguments.addAll(List.of(extra));
        return SpringApplication.run(OtakuLogApplication.class, arguments.toArray(String[]::new));
    }

    private JdbcTemplate jdbc(ConfigurableApplicationContext app) {
        return new JdbcTemplate(app.getBean(DataSource.class));
    }

    private AnimeDTO anime(String name, String status) {
        var value = new AnimeDTO();
        value.setName(name); value.setTotalEpisodes(3); value.setStatus(status); value.setScore(0.0); value.setSeason("2026秋");
        return value;
    }

    private AnimeMemoryDTO.WriteRequest memory(String key, String content, Long version) {
        return new AnimeMemoryDTO.WriteRequest(key, content, null, null, null, MemoryContext.NOTE, null, version);
    }

    private static class FictionalUpstream implements AutoCloseable {
        static final String COMMENT = "虚构外部原文，仅作导入核对";
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch aiEntered = new CountDownLatch(1), aiRelease = new CountDownLatch(1);
        final HttpServer server;

        FictionalUpstream() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var mapper = new ObjectMapper();
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                String path = exchange.getRequestURI().getPath();
                Object response;
                if (path.equals("/ai")) {
                    aiEntered.countDown();
                    try {
                        if (!aiRelease.await(25, TimeUnit.SECONDS)) throw new IllegalStateException("虚构模型响应等待超时");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("虚构模型响应被中断", interrupted);
                    }
                    response = Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", "{\"insights\":[]}"))),
                            "usage", Map.of("prompt_tokens", 9, "completion_tokens", 4));
                } else if (path.contains("/collections")) {
                    response = Map.of("total", 1, "data", List.of(Map.of("subject", Map.of("id", 982001, "name", "虚构收藏作品", "eps", 3),
                            "type", 2, "ep_status", 0, "rate", 8, "tags", List.of("剧情"), "comment", COMMENT)));
                } else if (path.equals("/v0/search/subjects")) {
                    response = Map.of("data", List.of(Map.of("id", 981001, "name", "虚构推荐作品")));
                } else {
                    response = Map.of("id", 981001, "type", 2, "name", "虚构推荐作品", "date", "2020-01-01",
                            "tags", List.of(Map.of("name", "剧情", "count", 1)));
                }
                byte[] bytes = mapper.writeValueAsBytes(response);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        String bangumiConfiguration() {
            return "--otakulog.bangumi.base-url=http://127.0.0.1:" + server.getAddress().getPort();
        }

        String[] aiConfiguration() {
            return new String[]{"--app.ai.enabled=true", "--app.ai.api-key=fictional-isolated-key",
                    "--app.ai.endpoint=http://127.0.0.1:" + server.getAddress().getPort() + "/ai", "--app.ai.response-timeout-ms=30000"};
        }

        @Override
        public void close() {
            aiRelease.countDown();
            server.stop(0);
        }
    }
}
