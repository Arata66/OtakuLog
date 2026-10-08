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
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        return SpringApplication.run(OtakuLogApplication.class,
                "--server.port=0", "--server.address=127.0.0.1",
                "--spring.datasource.url=" + url(database),
                "--spring.datasource.username=" + user, "--spring.datasource.password=" + password,
                "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
                "--spring.jpa.hibernate.ddl-auto=validate", "--spring.flyway.enabled=true",
                "--spring.flyway.locations=classpath:db/migration,classpath:com/otakulog",
                "--spring.flyway.out-of-order=true");
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
                assertEquals(1, read.ongoingCount()); assertEquals(1, read.todayAiringCount());
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
