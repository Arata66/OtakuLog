package com.otakulog.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otakulog.OtakuLogApplication;
import com.otakulog.entity.Anime;
import com.otakulog.entity.EpisodeRecord;
import com.otakulog.enums.EpisodeRecordSource;
import com.otakulog.repository.EpisodeRecordRepository;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.service.BackupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopSnapshotTest {
    @TempDir Path directory;
    @Test void 当写事务尚未完成时应该等待提交后再导出完整关联且回滚不触发备份() throws Exception {
        try (var app = new SpringApplication(OtakuLogApplication.class).run("--spring.profiles.active=desktop",
                "--app.desktop.data-dir=" + directory, "--spring.main.banner-mode=off", "--logging.level.root=WARN")) {
            var repository = app.getBean(AnimeRepository.class);
            var backup = app.getBean(BackupService.class);
            var manager = app.getBean(DesktopBackupManager.class);
            var transaction = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
            transaction.executeWithoutResult(status -> {
                var rolledBack = new Anime(); rolledBack.setName("虚构回滚作品"); repository.saveAndFlush(rolledBack); status.setRollbackOnly();
            });
            manager.flush(); assertEquals(0, repository.count()); assertNull(manager.status().lastSuccessfulDay());
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var workers = Executors.newFixedThreadPool(2);
            try {
                var write = workers.submit(() -> transaction.execute(status -> {
                    var anime = new Anime(); anime.setName("虚构快照屏障作品"); repository.saveAndFlush(anime);
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException failure) { throw new RuntimeException(failure); }
                    var episode = new EpisodeRecord(); episode.setAnimeId(anime.getId()); episode.setEpisodeNumber(1);
                    episode.setSource(EpisodeRecordSource.WATCHED); episode.setWatchedDate(LocalDate.of(2026, 10, 19));
                    app.getBean(EpisodeRecordRepository.class).saveAndFlush(episode);
                    return anime.getId();
                }));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var exported = workers.submit(backup::exportJson);
                assertThrows(TimeoutException.class, () -> exported.get(150, TimeUnit.MILLISECONDS));
                release.countDown(); long animeId = write.get(5, TimeUnit.SECONDS);
                var tree = new ObjectMapper().readTree(exported.get(5, TimeUnit.SECONDS));
                assertEquals("虚构快照屏障作品", tree.path("anime").get(0).path("data").path("name").asText());
                assertEquals(1, tree.path("episodes").size());
                assertEquals(tree.path("anime").get(0).path("key"), tree.path("episodes").get(0).path("animeKey"));
                manager.flush(); assertNotNull(manager.status().lastSuccessfulDay());
                Future<?> next;
                try (var snapshot = app.getBean(DesktopSnapshotGate.class).snapshot()) {
                    next = workers.submit(() -> transaction.executeWithoutResult(status -> {
                        var saved = repository.findByIdForUpdate(animeId).orElseThrow(); saved.setRemark("快照完成后才写入"); repository.saveAndFlush(saved);
                    }));
                    assertThrows(TimeoutException.class, () -> next.get(150, TimeUnit.MILLISECONDS));
                }
                next.get(5, TimeUnit.SECONDS); manager.flush(); assertEquals(1, repository.count());
                assertThrows(IllegalStateException.class, () -> transaction.execute(status -> backup.exportJson()), "禁止把写事务升级为导出锁而互相等待");
                var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(backup.exportJson());
                ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("anime").get(0).path("data")).remove("name");
                String damaged = invalid.toString();
                assertThrows(IllegalArgumentException.class, () -> backup.validateDocument(damaged));
                Path alternate = directory.resolve("损坏每日文件验收");
                Path original = alternate.resolve("daily/2026-10-19.json"); java.nio.file.Files.createDirectories(original.getParent());
                java.nio.file.Files.writeString(original, damaged);
                try (var checked = new DesktopBackupManager(alternate, java.time.Clock.fixed(java.time.Instant.parse("2026-10-19T04:00:00Z"), java.time.ZoneOffset.UTC), backup::exportJson, backup::validateDocument)) {
                    checked.initialize(); assertNull(checked.status().lastSuccessfulDay());
                    transaction.executeWithoutResult(status -> {
                        var saved = repository.findByIdForUpdate(animeId).orElseThrow(); saved.setRemark("校验损坏文件后的本地修改"); repository.saveAndFlush(saved);
                    });
                    checked.committed(); checked.flush(); assertNotNull(checked.status().warning());
                    assertNull(checked.status().lastSuccessfulDay()); assertEquals(damaged, java.nio.file.Files.readString(original));
                }
            } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
        }
    }
}
