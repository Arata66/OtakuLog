package com.otakulog.desktop;

import com.otakulog.OtakuLogApplication;
import com.otakulog.config.TasteAiProperties;
import com.otakulog.entity.Anime;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.repository.AnimeRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DesktopDatabaseTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void 当中文空格目录空库启动并重启时应该迁移校验且保留虚构作品() throws Exception {
        Path directory = temporaryDirectory.resolve("桌面资料 空库");
        Long animeId;
        String url;
        try (var application = startApplication(directory)) {
            assertEquals("validate", application.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"));
            assertEquals("127.0.0.1", application.getEnvironment().getProperty("server.address"));
            assertEquals("0", application.getEnvironment().getProperty("server.port"));
            assertTrue(((ServletWebServerApplicationContext) application).getWebServer().getPort() > 0);
            assertEquals("false", application.getEnvironment().getProperty("spring.h2.console.enabled"));
            var ai = application.getBean(TasteAiProperties.class);
            assertFalse(ai.isEnabled());
            assertEquals("", ai.getApiKey());

            var flyway = application.getBean(Flyway.class);
            assertEquals("1", flyway.info().current().getVersion().getVersion());
            assertEquals(1, flyway.info().all().length);
            assertEquals("classpath:db/desktop", flyway.getConfiguration().getLocations()[0].getDescriptor());
            assertFalse(flyway.getConfiguration().isBaselineOnMigrate());
            assertTrue(flyway.getConfiguration().isCleanDisabled());
            flyway.validate();

            var jdbc = new JdbcTemplate(application.getBean(DataSource.class));
            assertEquals(18, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'", Integer.class));
            assertEquals(7, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = 'PUBLIC'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_quota WHERE id = 1 AND reserved_calls = 0", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM import_lock WHERE id = 1", Integer.class));
            assertEquals("REGULAR", jdbc.queryForObject("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'MODE'", String.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC' AND DATA_TYPE = 'TIMESTAMP' AND DATETIME_PRECISION <> 6", Integer.class));

            Anime anime = fictionalAnime();
            animeId = application.getBean(AnimeRepository.class).saveAndFlush(anime).getId();
            try (var connection = application.getBean(DataSource.class).getConnection()) {
                url = connection.getMetaData().getURL();
                assertTrue(url.startsWith("jdbc:h2:file:"));
                assertTrue(url.replace('\\', '/').contains(directory.toRealPath().toString().replace('\\', '/')));
            }
        }

        assertTrue(Files.isRegularFile(directory.resolve("otakulog.mv.db")));
        try (var application = startApplication(directory)) {
            Anime restored = application.getBean(AnimeRepository.class).findById(animeId).orElseThrow();
            assertEquals("虚构桌面持久化验收", restored.getName());
            assertEquals(AnimeStatus.PLANNING, restored.getStatus());
            assertEquals(1L, application.getBean(AnimeRepository.class).count());
            assertEquals(1, application.getBean(Flyway.class).info().all().length);
        }
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT name FROM anime")) {
            assertTrue(result.next());
            assertEquals("虚构桌面持久化验收", result.getString(1));
        }
    }

    @Test
    void 当已有文件库缺少实体列时应该拒绝启动且保留已有数据() throws Exception {
        Path directory = temporaryDirectory.resolve("桌面校验资料");
        String url;
        try (var application = startApplication(directory)) {
            application.getBean(AnimeRepository.class).saveAndFlush(fictionalAnime());
            try (var connection = application.getBean(DataSource.class).getConnection()) {
                url = connection.getMetaData().getURL();
                connection.createStatement().execute("ALTER TABLE anime DROP COLUMN remark");
            }
        }
        RuntimeException failure = assertThrows(RuntimeException.class, () -> {
            try (var ignored = startApplication(directory)) {
                fail("缺列的文件库不应被自动修复或重建");
            }
        });
        assertTrue(messages(failure).contains("missing column [remark]"), messages(failure));
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM anime WHERE name = '虚构桌面持久化验收'")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    @Test
    void 当没有传入独立资料目录时应该拒绝启动并说明原因() {
        assertRejectedDirectory(null, "桌面资料目录");
    }

    @Test
    void 当资料目录使用相对路径或数据库选项分隔符时应该拒绝启动() {
        assertRejectedDirectory(Path.of("relative-desktop-data"), "绝对路径");
        Path directory = temporaryDirectory.resolve("错误资料;MODE=MySQL");
        assertRejectedDirectory(directory, "分号");
        assertFalse(Files.exists(directory));
    }

    @Test
    void 当资料目录指向已有文件时应该拒绝启动且保留文件() throws Exception {
        Path file = temporaryDirectory.resolve("不能覆盖的资料.txt");
        Files.writeString(file, "虚构且必须保留");
        assertRejectedDirectory(file, "桌面资料目录");
        assertEquals("虚构且必须保留", Files.readString(file));
    }

    private void assertRejectedDirectory(Path directory, String expectedMessage) {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> {
            try (var ignored = startApplication(directory)) {
                fail("非法桌面资料目录不应启动成功");
            }
        });
        assertTrue(messages(failure).contains(expectedMessage), messages(failure));
    }

    private ConfigurableApplicationContext startApplication(Path directory) {
        var application = new SpringApplication(OtakuLogApplication.class);
        application.setDefaultProperties(Map.of("AI_ENABLE", "true", "DEEPSEEK_API_KEY", "fictional-developer-key",
                "app.ai.enabled", "${AI_ENABLE:false}", "app.ai.api-key", "${DEEPSEEK_API_KEY:}",
                "server.port", "0", "server.address", "127.0.0.1"));
        var args = new ArrayList<>(List.of("--spring.profiles.active=desktop", "--spring.main.banner-mode=off"));
        if (directory != null) args.add("--app.desktop.data-dir=" + directory);
        return application.run(args.toArray(String[]::new));
    }

    private Anime fictionalAnime() {
        Anime anime = new Anime();
        anime.setName("虚构桌面持久化验收");
        anime.setCurrentEpisode(0);
        anime.setTotalEpisodes(12);
        anime.setStatus(AnimeStatus.PLANNING);
        return anime;
    }

    private String messages(Throwable failure) {
        var result = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            result.append(cause.getMessage()).append('\n');
        }
        return result.toString();
    }
}
