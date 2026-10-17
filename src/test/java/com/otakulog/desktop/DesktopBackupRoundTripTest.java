package com.otakulog.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.otakulog.OtakuLogApplication;
import com.otakulog.dto.*;
import com.otakulog.entity.*;
import com.otakulog.enums.*;
import com.otakulog.repository.*;
import com.otakulog.service.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class DesktopBackupRoundTripTest {
    private static final List<String> COLLECTIONS = List.of("anime", "tags", "groups", "memberships", "episodes",
            "memories", "insights", "aiCalls", "externalCollections", "importBatches",
            "recommendationPreferences", "recommendationFeedback", "recommendationBases");
    private static final String CREATED = "2024-01-02T03:04:05.123456";
    private static final String UPDATED = "2024-02-03T04:05:06.654321";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @TempDir Path temporaryDirectory;

    @Test
    @EnabledIfEnvironmentVariable(named = "OTAKULOG_MYSQL_TEST", matches = "true")
    void 当桌面库与MySQL比较真实名称和标签时应该按各自列规则搜索去重复用并升序() throws Exception {
        String database = "otakulog_verify_desktop_" + UUID.randomUUID().toString().replace("-", "");
        boolean owned = false;
        try (var upstream = new NoOutboundServer()) {
            try {
                try (var desktop = startDesktop(temporaryDirectory.resolve("大小写比较资料"), upstream)) {
                    assertEquals("REGULAR", jdbc(desktop).queryForObject(
                            "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME='MODE'", String.class));
                    for (String table : List.of("ANIME", "TAG")) {
                        assertEquals("CHARACTER VARYING", jdbc(desktop).queryForObject(
                                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME=? AND COLUMN_NAME='NAME'",
                                String.class, table));
                    }
                    assertTextComparison(desktop, "H2 REGULAR VARCHAR", false, false,
                            List.of("CaseAlpha", "CaseGamma", "casealpha", "casebeta"));
                }
                mysqlExecute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
                owned = true;
                try (var mysql = startMySql(database, upstream)) {
                    String nameCollation = mysqlNameCollation(mysql, database, "anime");
                    String tagCollation = mysqlNameCollation(mysql, database, "tag");
                    boolean nameIgnoresCase = ignoresAsciiCase(nameCollation);
                    boolean tagIgnoresCase = ignoresAsciiCase(tagCollation);
                    // 仅不区分大小写的受测列有此固定顺序；其他列直接对照真实列排序。
                    List<String> expectedOrder = nameIgnoresCase ? List.of("CaseAlpha", "casebeta", "CaseGamma") : null;
                    assertTextComparison(mysql, "MySQL库=" + database + ",anime.name=" + nameCollation + ",tag.name=" + tagCollation,
                            nameIgnoresCase, tagIgnoresCase, expectedOrder);
                }
            } finally {
                // 只有本次 CREATE 成功的随机库才归测试所有。
                if (owned) mysqlExecute("DROP DATABASE `" + database + "`");
            }
            assertEquals(0, upstream.calls.get(), "名称与标签比较不得请求模型或 Bangumi");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OTAKULOG_MYSQL_TEST", matches = "true")
    void 当MySQL全量版本六备份恢复到空文件库时应该保留十三集合全部语义并重映射引用() throws Exception {
        String database = "otakulog_verify_desktop_" + UUID.randomUUID().toString().replace("-", "");
        boolean owned = false;
        try (var upstream = new NoOutboundServer()) {
            try {
                mysqlExecute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
                owned = true;
                ObjectNode exported;
                try (var source = startMySql(database, upstream)) {
                    exported = fixture(source);
                }
                assertFullRoundTrip(exported, temporaryDirectory.resolve("MySQL迁移资料"), upstream);
            } finally {
                // 只有本次 CREATE 成功的随机库才归测试所有，失败时绝不清理别人的库。
                if (owned) mysqlExecute("DROP DATABASE `" + database + "`");
            }
            assertEquals(0, upstream.calls.get(), "备份与恢复不得请求模型或 Bangumi");
        }
    }

    @Test
    void 当全量版本六备份在两个真实文件库之间恢复并重启时应该保持所有集合与停用历史() throws Exception {
        try (var upstream = new NoOutboundServer()) {
            ObjectNode exported;
            try (var source = startDesktop(temporaryDirectory.resolve("源资料"), upstream)) {
                exported = fixture(source);
            }
            assertFullRoundTrip(exported, temporaryDirectory.resolve("目标资料"), upstream);
            assertEquals(0, upstream.calls.get());
        }
    }

    @Test
    void 当旧数组恢复到文件库时应该只生成未知日期导入记录且支持重复恢复() throws Exception {
        try (var upstream = new NoOutboundServer(); var app = startDesktop(temporaryDirectory.resolve("旧数组资料"), upstream)) {
            var backup = app.getBean(BackupService.class);
            String old = """
                    [{"name":"虚构旧数组","totalEpisodes":3,"currentEpisode":2,"status":"watching",
                    "season":"2020冬","score":8.0,"tags":"剧情,日常,剧情","remark":"虚构旧备注"}]
                    """;
            assertEquals(true, backup.previewJson(old).get("valid"));
            assertEquals(0, tree(backup).path("anime").size());
            backup.importJson(old);
            var exported = tree(backup);
            assertEquals(1, exported.path("anime").size());
            assertEquals("虚构旧备注", exported.path("anime").get(0).path("data").path("remark").asText());
            assertEquals(2, exported.path("tags").size());
            assertEquals(2, exported.path("episodes").size());
            for (var episode : exported.path("episodes")) {
                assertTrue(episode.path("watchedDate").isNull());
                assertEquals("IMPORT", episode.path("source").asText());
            }
            assertTrue(exported.path("memories").isEmpty());
            backup.importJson(old);
            assertEquals(normalize(exported), normalize(tree(backup)));
            assertEquals(0, upstream.calls.get());
        }
    }

    @Test
    void 当版本一到五恢复到空文件库时应该分别保留当版支持的全部集合() throws Exception {
        try (var upstream = new NoOutboundServer()) {
            ObjectNode full;
            try (var source = startDesktop(temporaryDirectory.resolve("旧版本源资料"), upstream)) {
                full = fixture(source);
            }
            for (int version = 1; version <= 5; version++) {
                var old = full.deepCopy();
                old.put("version", version);
                var expected = full.deepCopy();
                for (String field : unsupported(version)) {
                    old.remove(field);
                    expected.putArray(field);
                }
                try (var target = startDesktop(temporaryDirectory.resolve("版本" + version), upstream)) {
                    var backup = target.getBean(BackupService.class);
                    assertEquals(true, backup.previewJson(old.toString()).get("valid"), "版本" + version);
                    assertEmpty(backup);
                    backup.importJson(old.toString());
                    assertEquals(normalize(expected), normalize(tree(backup)), "版本" + version);
                    backup.importJson(old.toString());
                    assertEquals(normalize(expected), normalize(tree(backup)), "重复恢复版本" + version);
                }
            }
            assertEquals(0, upstream.calls.get());
        }
    }

    @Test
    void 当相同UUID内容不同并夹带新作品时应该预览和真实恢复整批拒绝且十三集合不变() throws Exception {
        try (var upstream = new NoOutboundServer(); var app = startDesktop(temporaryDirectory.resolve("冲突资料"), upstream)) {
            var backup = app.getBean(BackupService.class);
            var original = fixture(app);
            var mutations = new LinkedHashMap<String, Consumer<ObjectNode>>();
            mutations.put("记忆原文", doc -> first(doc, "memories").put("content", "虚构不同原文：人物选择"));
            mutations.put("模型原建议", doc -> ((ObjectNode) doc.path("insights").get(1)).put("suggestedStatement", "虚构不同模型建议"));
            mutations.put("调用未知用量", doc -> first(doc, "aiCalls").put("reasoningTokens", 2));
            mutations.put("外部原文版本", doc -> first(doc, "externalCollections").put("version", 9));
            mutations.put("导入旧归属", doc -> first(doc, "importBatches").put("owner", "不同旧归属"));
            mutations.put("推荐永久条件", doc -> ((ObjectNode) first(doc, "recommendationPreferences").path("settings")).put("minYear", 2001));
            mutations.put("反馈撤销版本", doc -> first(doc, "recommendationFeedback").put("version", 9));
            mutations.put("关联来源指纹", doc -> first(doc, "recommendationBases").put("sourceFingerprint", "f".repeat(64)));
            for (var mutation : mutations.entrySet()) {
                var incoming = original.deepCopy();
                addNewAnime(incoming);
                mutation.getValue().accept(incoming);
                assertEquals(6, backup.previewJson(incoming.toString()).get("version"), "应通过格式校验后识别内容冲突：" + mutation.getKey());
                assertRejectedWithoutChanges(backup, incoming.toString(), original, mutation.getKey());
            }
            assertEquals(0, upstream.calls.get());
        }
    }

    @Test
    void 当JSON损坏缺字段或枚举非法时应该拒绝且没有任何部分写入() throws Exception {
        try (var upstream = new NoOutboundServer(); var app = startDesktop(temporaryDirectory.resolve("无效备份资料"), upstream)) {
            var backup = app.getBean(BackupService.class);
            var original = fixture(app);
            var incoming = original.deepCopy();
            addNewAnime(incoming);
            assertRejectedWithoutChanges(backup, incoming.toString().substring(0, incoming.toString().length() - 1), original, "损坏JSON");
            for (String collection : COLLECTIONS) {
                var missing = incoming.deepCopy();
                missing.remove(collection);
                assertRejectedWithoutChanges(backup, missing.toString(), original, "缺集合" + collection);
            }
            for (String collection : List.of("memories", "insights", "aiCalls", "externalCollections", "importBatches", "recommendationFeedback", "recommendationBases")) {
                var missing = incoming.deepCopy();
                first(missing, collection).remove("createdAt");
                assertRejectedWithoutChanges(backup, missing.toString(), original, "缺审计" + collection);
            }
            for (var field : Map.of("episodes", "source", "memories", "context", "insights", "direction", "aiCalls", "status",
                    "recommendationFeedback", "kind", "recommendationBases", "kind").entrySet()) {
                var invalid = incoming.deepCopy();
                first(invalid, field.getKey()).put(field.getValue(), "UNKNOWN");
                assertRejectedWithoutChanges(backup, invalid.toString(), original, "非法枚举" + field.getKey());
            }
            var invalidAnime = incoming.deepCopy();
            ((ObjectNode) first(invalidAnime, "anime").path("data")).put("status", "UNKNOWN");
            assertRejectedWithoutChanges(backup, invalidAnime.toString(), original, "非法作品状态");
            assertEquals(0, upstream.calls.get());
        }
    }

    @Test
    void 当恢复运行中的调用并重试重启时应该只作历史不外发不降低当天额度且内部标记不进入备份() throws Exception {
        Path directory = temporaryDirectory.resolve("调用历史资料");
        String key = UUID.randomUUID().toString();
        Long animeId;
        Long memoryId;
        TasteAiDTO.Request request;
        ObjectNode running;
        try (var upstream = new NoOutboundServer()) {
            try (var app = startDesktop(directory, upstream, "--app.ai.enabled=true", "--app.ai.api-key=fictional-desktop-key")) {
                var anime = app.getBean(AnimeService.class).addAnime(anime("虚构运行历史"));
                var memory = app.getBean(AnimeMemoryService.class).create(anime.getId(), memory("虚构人物选择", null));
                animeId = anime.getId(); memoryId = memory.id();
                request = new TasteAiDTO.Request(memory.version(), null, key);
                var preview = app.getBean(TasteAiService.class).preview(animeId, memoryId, request);
                var call = historicalCall(memory.key(), key, AiCallStatus.RUNNING);
                call.setSourceVersion(memory.version()); call.setInputHash(preview.inputHash());
                call.setSelectedCharacters(preview.selectedCharacters()); call.setModel(preview.model()); call.setPromptVersion(preview.promptVersion());
                call.setCreatedAt(LocalDate.now().atStartOfDay().plusNanos(123456000));
                call.setUpdatedAt(call.getCreatedAt());
                var doc = tree(app.getBean(BackupService.class));
                ((ArrayNode) doc.path("aiCalls")).add(mapper.valueToTree(new BackupDocument.AiCallEntry(key, memory.key(), call.getSourceVersion(),
                        call.getInputHash(), call.getModel(), call.getPromptVersion(), call.getSelectedCharacters(), AiCallStatus.RUNNING,
                        null, null, null, null, null, call.getCreatedAt(), call.getUpdatedAt(), null)));
                jdbc(app).update("UPDATE ai_quota SET quota_date=?,reserved_calls=7 WHERE id=1", LocalDate.now());
                var backup = app.getBean(BackupService.class);
                assertEquals(true, backup.previewJson(doc.toString()).get("valid"));
                backup.importJson(doc.toString());
                assertTrue(app.getBean(AiCallRepository.class).findByRequestKey(key).orElseThrow().isHistoryOnly());
                assertEquals(7, app.getBean(AiQuotaRepository.class).findById(1L).orElseThrow().getReservedCalls());
                assertTrue(app.getBean(TasteAiService.class).extract(animeId, memoryId, request).message().contains("结果未知"));
                running = tree(backup);
                assertEquals(mapper.readTree(doc.toString()).path("aiCalls"), running.path("aiCalls"));
                assertFalse(first(running, "aiCalls").has("historyOnly"));
                backup.importJson(doc.toString());
                backup.importJson("[]");
                assertEquals(7, app.getBean(AiQuotaRepository.class).findById(1L).orElseThrow().getReservedCalls());
            }
            try (var app = startDesktop(directory, upstream, "--app.ai.enabled=true", "--app.ai.api-key=fictional-desktop-key")) {
                var ai = app.getBean(TasteAiService.class);
                assertEquals(AiCallStatus.RUNNING, ai.get(key).status());
                assertTrue(ai.extract(animeId, memoryId, request).message().contains("结果未知"));
                assertEquals(normalize(running), normalize(tree(app.getBean(BackupService.class))));
                assertEquals(7, app.getBean(AiQuotaRepository.class).findById(1L).orElseThrow().getReservedCalls());
                assertEquals(1, app.getBean(AiCallRepository.class).count());
            }
            assertEquals(0, upstream.calls.get());
        }
    }

    private void assertTextComparison(ConfigurableApplicationContext app, String label, boolean nameIgnoresCase,
                                      boolean tagIgnoresCase, List<String> expectedOrder) {
        var service = app.getBean(AnimeService.class);
        var alpha = service.addAnime(anime("CaseAlpha"));
        assertEquals("duplicate_name", assertThrows(IllegalArgumentException.class,
                () -> service.addAnime(anime("CaseAlpha"))).getMessage(), label);
        if (nameIgnoresCase) {
            assertEquals("duplicate_name", assertThrows(IllegalArgumentException.class,
                    () -> service.addAnime(anime("casealpha"))).getMessage(), label);
        } else {
            service.addAnime(anime("casealpha"));
        }
        var beta = service.addAnime(anime("casebeta"));
        var gamma = service.addAnime(anime("CaseGamma"));
        var sorted = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "name"));
        List<String> all = service.searchAnimePaged(null, null, sorted, null).stream().map(AnimeVO::getName).toList();
        assertEquals(jdbc(app).queryForList("SELECT name FROM anime ORDER BY name ASC", String.class), all, label);
        if (expectedOrder != null) assertEquals(expectedOrder, all, label);
        List<String> lowerSearch = service.searchAnimePaged("case", null, sorted, null).stream().map(AnimeVO::getName).toList();
        List<String> upperSearch = service.searchAnimePaged("CASE", null, sorted, null).stream().map(AnimeVO::getName).toList();
        assertEquals(nameIgnoresCase ? all : List.of("casealpha", "casebeta"), lowerSearch, label);
        assertEquals(nameIgnoresCase ? all : List.of(), upperSearch, label);

        service.addTagToAnime(alpha.getId(), "CaseTag");
        service.addTagToAnime(beta.getId(), "CaseTag");
        service.addTagToAnime(gamma.getId(), "casetag");
        service.addTagToAnime(gamma.getId(), "casetag");
        var tags = new TreeMap<String, Integer>();
        service.getAllTagsWithCount().forEach(tag -> tags.put(tag.getName(), tag.getCount()));
        assertEquals(tagIgnoresCase ? Map.of("CaseTag", 3) : Map.of("CaseTag", 2, "casetag", 1), tags, label);
        assertEquals(tagIgnoresCase ? all.stream().filter(name -> !name.equals("casealpha")).toList() : List.of("CaseGamma"),
                service.searchAnimePaged(null, null, sorted, "casetag").stream().map(AnimeVO::getName).toList(), label);
        assertEquals(nameIgnoresCase ? 3L : 4L, app.getBean(AnimeRepository.class).count(), label);
        assertEquals(tagIgnoresCase ? 1L : 2L, app.getBean(TagRepository.class).count(), label);
        System.out.println("文本比较验收：" + label + "; 同名拒绝=true; 大小写异名拒绝=" + nameIgnoresCase
                + "; case搜索=" + lowerSearch + "; CASE搜索=" + upperSearch + "; name升序=" + all + "; 标签及引用数=" + tags);
    }

    private String mysqlNameCollation(ConfigurableApplicationContext app, String database, String table) {
        return jdbc(app).queryForObject("SELECT COLLATION_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME='name'", String.class, database, table);
    }

    private boolean ignoresAsciiCase(String collation) {
        assertNotNull(collation);
        assertTrue(collation.endsWith("_ci") || collation.endsWith("_cs") || collation.endsWith("_bin"),
                "需要明确新增受测列的 ASCII 比较规则：" + collation);
        return collation.endsWith("_ci");
    }

    private ObjectNode fixture(ConfigurableApplicationContext app) throws Exception {
        var service = app.getBean(AnimeService.class);
        var discarded = service.addAnime(anime("虚构自增空洞"));
        service.deleteAnime(discarded.getId());
        var tags = app.getBean(TagRepository.class);
        tags.delete(tags.saveAndFlush(new Tag("虚构已删除标签")));
        var groups = app.getBean(AnimeGroupRepository.class);
        var discardedGroup = new AnimeGroup(); discardedGroup.setName("虚构已删除分组");
        groups.delete(groups.saveAndFlush(discardedGroup));
        var input = anime("虚构跨库往返"); input.setTags("剧情,日常"); input.setBangumiId(986001);
        Long animeId = service.addAnime(input).getId();
        service.updateStatus(animeId, AnimeStatus.FINISHED);
        var history = app.getBean(EpisodeHistoryService.class);
        var snapshot = history.get(animeId, 0, 20).entries().get(0);
        history.update(animeId, 1, new EpisodeHistoryDTO.UpdateRequest(LocalDate.of(2023, 4, 5), snapshot));
        var memoryService = app.getBean(AnimeMemoryService.class);
        var memory = memoryService.create(animeId, memory("虚构人物选择细腻\n重看仍有不同感受", null));
        memory = memoryService.update(animeId, memory.id(), memory("虚构人物选择细腻\n重看仍有不同感受，另有节奏不足", memory.version()));
        var insightService = app.getBean(MemoryInsightService.class);
        var current = insightService.create(animeId, memory.id(), new MemoryInsightDTO.WriteRequest(UUID.randomUUID().toString(), memory.version(),
                InsightQuoteField.CONTENT, "人物选择", "虚构用户确认喜欢人物选择", InsightDirection.LIKE, InsightAspect.CHARACTER, "人物选择", InsightStatus.CONFIRMED, null));
        var basisService = app.getBean(RecommendationBasisService.class);
        var source = basisService.get().sources().get(0);
        String basisKey = UUID.randomUUID().toString();
        basisService.save(new RecommendationBasisDTO.Write(basisKey, current.key(), source.sourceVersion(), source.insightVersion(), source.sourceFingerprint(),
                source.direction(), RecommendationBasisDTO.Kind.TAG, "剧情", null, true, 0L));
        basisService.disable(basisKey, 0);
        var settings = app.getBean(RecommendationState.class).save(new RecommendationDTO.Settings(null, 0L, 2000, 2025, List.of("机战"), List.of("剧情", "日常"), true));
        app.getBean(RecommendationState.class).save(new RecommendationDTO.Settings(settings.key(), settings.version(), 2000, 2025,
                settings.excludedTags(), settings.recallTags(), false));
        var group = new AnimeGroup(); group.setName("虚构收藏分组"); group.setDescription("虚构跨库分组说明"); group.setColor("#123456"); group.setSortOrder(3);
        groups.saveAndFlush(group);
        jdbc(app).update("INSERT INTO anime_group_relation(group_id,anime_id) VALUES (?,?)", group.getId(), animeId);

        // 历史原模型建议、旧账号归属和已撤销反馈不能通过当前界面重新生成，显式构造虚构历史。
        String callKey = UUID.randomUUID().toString();
        app.getBean(AiCallRepository.class).saveAndFlush(historicalCall(memory.key(), callKey, AiCallStatus.SUCCEEDED));
        var old = new MemoryInsight(); old.setInsightKey(UUID.randomUUID().toString()); old.setMemoryId(memory.id()); old.setSourceVersion(0L);
        old.setQuoteField(InsightQuoteField.CONTENT); old.setQuote("旧版虚构人物"); old.setStatement("虚构用户改写，不认可模型原判断");
        old.setDirection(InsightDirection.DISLIKE); old.setAspect(InsightAspect.PACING); old.setFactor("虚构节奏"); old.setStatus(InsightStatus.REJECTED);
        old.setOrigin(InsightOrigin.AI); old.setAiCallKey(callKey); old.setSuggestedQuoteField(InsightQuoteField.CONTENT); old.setSuggestedQuote("更旧虚构人物");
        old.setSuggestedStatement("虚构模型原建议"); old.setSuggestedDirection(InsightDirection.LIKE); old.setSuggestedAspect(InsightAspect.CHARACTER);
        old.setSuggestedFactor("虚构人物关系"); old.setVersion(3L); audit(old);
        app.getBean(MemoryInsightRepository.class).saveAndFlush(old);
        var external = new ExternalCollection(); external.setCollectionKey(UUID.randomUUID().toString()); external.setUsername("fictional-old-user");
        external.setSubjectId(986001); external.setCollectionType(99); external.setVersion(2L); external.setCapturedAt(LocalDateTime.parse(CREATED)); audit(external);
        external.setPayload(app.getBean(ObjectMapper.class).writeValueAsString(new BangumiImportDTO.CollectionData(986001, "虚构原始名称", "虚构中文名称", 99,
                null, null, 8, List.of("旧原文标签", "剧情"), "虚构外部评论，不猜观看日期", "", "/fictional-cover.jpg", "虚构来源时间未知")));
        app.getBean(ExternalCollectionRepository.class).saveAndFlush(external);
        var batch = new ImportBatch(); batch.setRequestKey(UUID.randomUUID().toString()); batch.setOwner("fictional-old-owner");
        batch.setUsername(external.getUsername()); batch.setFingerprint("b".repeat(64)); audit(batch);
        batch.setResultJson(mapper.writeValueAsString(new BangumiImportDTO.Result(batch.getRequestKey(), 0, 1, 1, 1, 1)));
        app.getBean(ImportBatchRepository.class).saveAndFlush(batch);
        var feedback = new RecommendationFeedback(); feedback.setFeedbackKey(UUID.randomUUID().toString()); feedback.setOwner("fictional-old-owner");
        feedback.setSourceBatchKey(UUID.randomUUID().toString()); feedback.setSubjectId(986002); feedback.setName("虚构已撤销推荐");
        feedback.setKind(RecommendationDTO.Kind.NOT_INTERESTED); feedback.setActive(false); feedback.setVersion(3L); audit(feedback);
        app.getBean(RecommendationFeedbackRepository.class).saveAndFlush(feedback);
        for (String table : List.of("anime", "tag", "anime_group", "episode_record", "anime_memory", "memory_insight", "recommendation_preference", "recommendation_basis")) {
            if (table.equals("tag")) jdbc(app).update("UPDATE tag SET created_at=?", LocalDateTime.parse(CREATED));
            else jdbc(app).update("UPDATE " + table + " SET created_at=?,updated_at=?", LocalDateTime.parse(CREATED), LocalDateTime.parse(UPDATED));
        }
        var backup = app.getBean(BackupService.class);
        var result = tree(backup);
        for (String collection : COLLECTIONS) assertFalse(result.path(collection).isEmpty(), collection);
        assertEquals(true, backup.previewJson(result.toString()).get("valid"), "夹具必须满足真实恢复校验");
        return result;
    }

    private void assertFullRoundTrip(ObjectNode exported, Path directory, NoOutboundServer upstream) throws Exception {
        try (var target = startDesktop(directory, upstream)) {
            assertEquals("validate", target.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"));
            var flyway = target.getBean(org.flywaydb.core.Flyway.class);
            flyway.validate();
            assertNotNull(flyway.info().current());
            var backup = target.getBean(BackupService.class);
            assertEmpty(backup);
            assertEquals(true, backup.previewJson(exported.toString()).get("valid"));
            assertEmpty(backup);
            backup.importJson(exported.toString());
            var actual = tree(backup);
            for (String collection : List.of("anime", "tags", "groups"))
                assertNotEquals(first(exported, collection).path("key"), first(actual, collection).path("key"), "自增引用应重新映射：" + collection);
            assertEquals(normalize(exported), normalize(actual));
            assertTrue(actual.path("episodes").get(1).path("watchedDate").isNull());
            assertFalse(first(actual, "recommendationBases").path("enabled").asBoolean());
            assertFalse(first(actual, "recommendationFeedback").path("active").asBoolean());
            backup.importJson(exported.toString());
            assertEquals(normalize(exported), normalize(tree(backup)));
        }
        try (var target = startDesktop(directory, upstream)) {
            assertEquals(normalize(exported), normalize(tree(target.getBean(BackupService.class))));
        }
        try (var files = Files.walk(directory)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".mv.db")));
        }
    }

    private void assertRejectedWithoutChanges(BackupService backup, String json, ObjectNode original, String reason) throws Exception {
        assertEquals(false, backup.previewJson(json).get("valid"), reason);
        assertEquals(normalize(original), normalize(tree(backup)), "预览写入了数据：" + reason);
        assertThrows(IllegalArgumentException.class, () -> backup.importJson(json), reason);
        assertEquals(normalize(original), normalize(tree(backup)), "拒绝后部分写入：" + reason);
    }

    private ObjectNode normalize(ObjectNode input) {
        var result = input.deepCopy();
        result.remove("exportedAt");
        var anime = referenceNames(result.path("anime"), row -> row.path("data").path("name").asText());
        var tags = referenceNames(result.path("tags"), row -> row.path("name").asText());
        var groups = referenceNames(result.path("groups"), row -> row.path("name").asText());
        for (var row : result.path("anime")) {
            replaceReference((ObjectNode) row, "key", anime);
            var values = new ArrayList<String>(); row.path("tagKeys").forEach(key -> values.add(tags.get(key.asText())));
            values.sort(String::compareTo); var array = ((ObjectNode) row).putArray("tagKeys"); values.forEach(array::add);
        }
        for (var row : result.path("tags")) replaceReference((ObjectNode) row, "key", tags);
        for (var row : result.path("groups")) replaceReference((ObjectNode) row, "key", groups);
        for (String collection : List.of("memberships", "episodes", "memories")) for (var row : result.path(collection)) {
            replaceReference((ObjectNode) row, "animeKey", anime);
            if (collection.equals("memberships")) replaceReference((ObjectNode) row, "groupKey", groups);
        }
        // 仅根集合顺序无业务含义；UUID、审计、指纹、原文以及嵌套业务数组原样保留。
        for (String collection : COLLECTIONS) {
            var rows = new ArrayList<JsonNode>(); result.path(collection).forEach(rows::add);
            rows.sort(Comparator.comparing(JsonNode::toString));
            result.set(collection, mapper.valueToTree(rows));
        }
        return result;
    }

    private Map<String, String> referenceNames(JsonNode rows, java.util.function.Function<JsonNode, String> identity) {
        var result = new HashMap<String, String>();
        for (var row : rows) result.put(row.path("key").asText(), identity.apply(row));
        return result;
    }

    private void replaceReference(ObjectNode row, String field, Map<String, String> references) {
        assertTrue(references.containsKey(row.path(field).asText()), "无效文件内引用" + field);
        row.put(field, references.get(row.path(field).asText()));
    }

    private List<String> unsupported(int version) {
        var fields = new ArrayList<>(List.of("recommendationBases"));
        if (version < 5) fields.addAll(List.of("recommendationPreferences", "recommendationFeedback"));
        if (version < 4) fields.addAll(List.of("externalCollections", "importBatches"));
        if (version < 3) fields.addAll(List.of("insights", "aiCalls"));
        if (version < 2) fields.add("memories");
        return fields;
    }

    private void addNewAnime(ObjectNode doc) {
        var entry = first(doc, "anime").deepCopy(); entry.put("key", "a:fictional-new");
        ((ObjectNode) entry.path("data")).put("name", "虚构不得部分新增").put("bangumiId", 986099);
        ((ArrayNode) doc.path("anime")).insert(0, entry);
    }

    private AiCall historicalCall(String memoryKey, String key, AiCallStatus status) {
        var call = new AiCall(); call.setRequestKey(key); call.setMemoryKey(memoryKey); call.setSourceVersion(0L);
        call.setInputHash("a".repeat(64)); call.setModel("deepseek-flash"); call.setPromptVersion("taste-v1");
        call.setSelectedCharacters(8); call.setStatus(status); audit(call);
        if (status != AiCallStatus.RUNNING) { call.setCompletedAt(LocalDateTime.parse(UPDATED)); call.setPromptTokens(11); call.setCompletionTokens(7); }
        return call;
    }

    private void audit(BaseEntity value) { value.setCreatedAt(LocalDateTime.parse(CREATED)); value.setUpdatedAt(LocalDateTime.parse(UPDATED)); }
    private ObjectNode first(ObjectNode doc, String collection) { return (ObjectNode) doc.path(collection).get(0); }
    private ObjectNode tree(BackupService backup) throws Exception { return (ObjectNode) mapper.readTree(backup.exportJson()); }
    private void assertEmpty(BackupService backup) throws Exception { var doc = tree(backup); for (String field : COLLECTIONS) assertTrue(doc.path(field).isEmpty(), field); }
    private JdbcTemplate jdbc(ConfigurableApplicationContext app) { return new JdbcTemplate(app.getBean(DataSource.class)); }
    private AnimeDTO anime(String name) {
        var anime = new AnimeDTO(); anime.setName(name); anime.setTotalEpisodes(3); anime.setSeason("2024冬");
        anime.setStatus("planning"); anime.setScore(8.5); anime.setRemark("虚构备注"); anime.setCoverUrl("/fictional-cover.jpg"); return anime;
    }
    private AnimeMemoryDTO.WriteRequest memory(String content, Long version) {
        return new AnimeMemoryDTO.WriteRequest(version == null ? UUID.randomUUID().toString() : null, content, "虚构演出", "虚构节奏", "全篇", MemoryContext.REWATCH, null, version);
    }

    private ConfigurableApplicationContext startDesktop(Path directory, NoOutboundServer upstream, String... extra) {
        var args = commonArguments(upstream);
        args.addAll(List.of("--spring.profiles.active=desktop", "--app.desktop.data-dir=" + directory));
        for (String option : extra) {
            String prefix = option.substring(0, option.indexOf('=') + 1);
            args.removeIf(existing -> existing.startsWith(prefix));
            args.add(option);
        }
        return SpringApplication.run(OtakuLogApplication.class, args.toArray(String[]::new));
    }

    private ConfigurableApplicationContext startMySql(String database, NoOutboundServer upstream) {
        var args = commonArguments(upstream);
        args.addAll(List.of("--spring.profiles.active=backup-compatibility", "--spring.datasource.url=" + mysqlUrl(database),
                "--spring.datasource.username=" + System.getenv().getOrDefault("DB_USER", "root"),
                "--spring.datasource.password=" + System.getenv().getOrDefault("DB_PASS", "123456"),
                "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver", "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
                "--spring.jpa.hibernate.ddl-auto=validate", "--spring.flyway.enabled=true",
                "--spring.flyway.locations=classpath:db/migration,classpath:com/otakulog", "--spring.flyway.out-of-order=true"));
        return SpringApplication.run(OtakuLogApplication.class, args.toArray(String[]::new));
    }

    private ArrayList<String> commonArguments(NoOutboundServer upstream) {
        return new ArrayList<>(List.of("--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--logging.level.root=WARN", "--app.ai.enabled=false", "--app.ai.api-key=", "--app.ai.endpoint=" + upstream.url(),
                "--otakulog.bangumi.base-url=" + upstream.url()));
    }

    private String mysqlUrl(String database) {
        return "jdbc:mysql://127.0.0.1:3306/" + database + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    }

    private void mysqlExecute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(mysqlUrl(""), System.getenv().getOrDefault("DB_USER", "root"), System.getenv().getOrDefault("DB_PASS", "123456"));
             var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static class NoOutboundServer implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        final HttpServer server;
        NoOutboundServer() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.incrementAndGet(); exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(500, -1); exchange.close();
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
