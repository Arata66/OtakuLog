package com.otakulog.deployment;

import com.otakulog.OtakuLogApplication;
import com.otakulog.entity.Anime;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.dto.AnimeDTO;
import com.otakulog.service.AnimeService;
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

    private Flyway flyway(String database, String target) {
        return Flyway.configure().dataSource(url(database), user, password)
                .locations("classpath:db/migration", "classpath:com/otakulog")
                .outOfOrder(true).target(target).load();
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
