package com.otakulog.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otakulog.entity.Anime;
import com.otakulog.entity.EpisodeRecord;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.enums.EpisodeRecordSource;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.EpisodeRecordRepository;
import com.otakulog.service.BangumiService;
import com.otakulog.service.DailyAiringService;
import com.otakulog.service.DailyWatchService;
import com.otakulog.dto.DailyWatchDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:daily_watch;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@WithMockUser
class DailyWatchTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AnimeRepository anime;
    @Autowired private EpisodeRecordRepository records;
    @MockBean private BangumiService bangumi;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void 清理隔离数据() { records.deleteAll(); anime.deleteAll(); }

    @Test
    void 当首页没有追中作品时应该返回带日期的空入口() throws Exception {
        JsonNode result = daily();
        assertEquals(today.toString(), result.path("date").asText());
        assertEquals(today.getDayOfWeek().getValue(), result.path("weekday").asInt());
        assertEquals(0, result.path("ongoingCount").asInt());
        assertTrue(result.path("continueWatching").isEmpty());
        assertTrue(result.path("todayAiring").isEmpty());
    }

    @Test
    void 当作品状态或进度不适合继续时应该排除() throws Exception {
        add("有效追中", AnimeStatus.WATCHING, 1, 12, null, 0);
        add("计划", AnimeStatus.PLANNING, 0, 12, null, 0);
        add("完成", AnimeStatus.FINISHED, 12, 12, null, 0);
        add("放弃", AnimeStatus.DROPPED, 2, 12, null, 0);
        add("追中已满", AnimeStatus.WATCHING, 12, 12, null, 0);
        add("负进度", AnimeStatus.WATCHING, -1, 12, null, 0);
        add("未知总数", AnimeStatus.WATCHING, 1, null, null, 0);
        JsonNode result = daily();
        assertEquals(1, result.path("ongoingCount").asInt());
        assertEquals("有效追中", result.path("continueWatching").get(0).path("anime").path("name").asText());
    }

    @Test
    void 当存在明确日期时应该按日期排序且不以资料更新时间替代() throws Exception {
        Long old = add("旧日期", AnimeStatus.WATCHING, 1, 12, null, 0);
        Long recent = add("最近观看", AnimeStatus.WATCHING, 1, 12, null, 50);
        record(old, today.minusDays(10), EpisodeRecordSource.WATCHED);
        record(recent, today.minusDays(1), EpisodeRecordSource.MANUAL);
        JsonNode entries = daily().path("continueWatching");
        assertEquals(recent.longValue(), entries.get(0).path("anime").path("id").asLong());
        assertEquals(today.minusDays(1).toString(), entries.get(0).path("lastWatchedDate").asText());
    }

    @Test
    void 当记录为历史来源未知日期或未来时应该不假定最近观看日期() throws Exception {
        record(add("旧估算", AnimeStatus.WATCHING, 1, 12, null, 1), today, EpisodeRecordSource.LEGACY);
        record(add("未知", AnimeStatus.WATCHING, 1, 12, null, 2), null, EpisodeRecordSource.IMPORT);
        record(add("未来", AnimeStatus.WATCHING, 1, 12, null, 3), today.plusDays(1), EpisodeRecordSource.WATCHED);
        for (JsonNode entry : daily().path("continueWatching")) assertTrue(entry.path("lastWatchedDate").isNull());
    }

    @Test
    void 当没有明确观看日期时应该按手动排序名称和编号稳定返回() throws Exception {
        add("乙", AnimeStatus.WATCHING, 0, 12, null, 2);
        Long first = add("甲", AnimeStatus.WATCHING, 0, 12, null, 1);
        Long second = add("甲", AnimeStatus.WATCHING, 0, 12, null, 1);
        JsonNode entries = daily().path("continueWatching");
        assertEquals(first.longValue(), entries.get(0).path("anime").path("id").asLong());
        assertEquals(second.longValue(), entries.get(1).path("anime").path("id").asLong());
    }

    @Test
    void 当今日放送但尚未开播时应该排除今日参考并保留追中入口() throws Exception {
        int day = today.getDayOfWeek().getValue();
        add("今日", AnimeStatus.WATCHING, 1, 12, day, 1);
        Long future = add("尚未开播", AnimeStatus.WATCHING, 1, 12, day, 2);
        Anime a = anime.findById(future).orElseThrow(); a.setStartDate(today.plusDays(7)); anime.saveAndFlush(a);
        add("其他日", AnimeStatus.WATCHING, 1, 12, day == 7 ? 1 : day + 1, 3);
        when(bangumi.getCalendar()).thenReturn(List.of(entry(101, "今日", day), entry(102, "尚未开播", day),
                entry(103, "其他日", day == 7 ? 1 : day + 1)));
        JsonNode result = airing();
        assertEquals(3, result.path("ongoingCount").asInt());
        assertEquals(1, result.path("todayAiringCount").asInt());
        assertEquals("今日", result.path("todayAiring").get(0).path("anime").path("name").asText());
    }

    @Test
    void 当超过首页显示数量时应该只返回六部且总数准确() throws Exception {
        for (int i = 0; i < 8; i++) add("追中" + i, AnimeStatus.WATCHING, 1, 12, today.getDayOfWeek().getValue(), i);
        when(bangumi.getCalendar()).thenReturn(IntStream.range(0, 8)
                .mapToObj(i -> entry(100 + i, "追中" + i, today.getDayOfWeek().getValue())).toList());
        JsonNode result = airing();
        assertEquals(8, result.path("ongoingCount").asInt()); assertEquals(8, result.path("todayAiringCount").asInt());
        assertEquals(6, result.path("continueWatching").size()); assertEquals(6, result.path("todayAiring").size());
    }

    @Test
    void 当打开日常入口时应该不调用外部服务或改写已有记录() throws Exception {
        Long id = add("只读验收", AnimeStatus.WATCHING, 1, 12, null, 0);
        record(id, today.minusDays(1), EpisodeRecordSource.LEGACY);
        var before = anime.findById(id).orElseThrow().getUpdatedAt(); daily(); daily();
        assertEquals(before, anime.findById(id).orElseThrow().getUpdatedAt());
        assertNull(anime.findById(id).orElseThrow().getBroadcastDay());
        assertEquals(1, records.count());
        assertEquals(EpisodeRecordSource.LEGACY, records.findByAnimeIdAndEpisodeNumber(id, 1).orElseThrow().getSource());
        verifyNoInteractions(bangumi);
    }

    @Test
    @WithAnonymousUser
    void 当未登录时应该拒绝读取日常入口() throws Exception {
        mvc.perform(get("/api/watch/daily")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/watch/airing")).andExpect(status().isUnauthorized());
    }

    @Test
    void 当旧番只保留历史放送星期时应该先显示继续观看而不提示今日放送() throws Exception {
        Long id = add("已完结旧番", AnimeStatus.WATCHING, 1, 12, today.getDayOfWeek().getValue(), 0);
        Anime a = anime.findById(id).orElseThrow(); a.setStartDate(today.minusMonths(8)); anime.saveAndFlush(a);
        JsonNode result = daily();
        assertEquals(1, result.path("ongoingCount").asInt());
        assertTrue(result.path("todayAiring").isEmpty());
        assertTrue(result.path("todayAiringCount").isNull());
        assertEquals("UNVERIFIED", result.path("airingStatus").asText());
        verifyNoInteractions(bangumi);
    }

    @Test
    void 当当前日历没有旧番时应该排除今日参考并保留补番入口() throws Exception {
        int day = today.getDayOfWeek().getValue();
        linked("旧番", 101, day); linked("仍在播", 102, day);
        when(bangumi.getCalendar()).thenReturn(List.of(entry(102, "仍在播", day)));
        JsonNode result = airing();
        assertEquals("VERIFIED", result.path("airingStatus").asText());
        assertEquals(2, result.path("ongoingCount").asInt());
        assertEquals(1, result.path("todayAiringCount").asInt());
        assertEquals("仍在播", result.path("todayAiring").get(0).path("anime").path("name").asText());
    }

    @Test
    void 当日历星期与本地旧星期不同时应该以当前日历为准且不写回() throws Exception {
        int day = today.getDayOfWeek().getValue(), other = day == 7 ? 1 : day + 1;
        Long id = linked("长篇连载", 101, other);
        Anime a = anime.findById(id).orElseThrow(); a.setStartDate(today.minusYears(2)); a.setEndDate(today.minusMonths(6)); anime.saveAndFlush(a);
        when(bangumi.getCalendar()).thenReturn(List.of(entry(101, "不同译名", day)));
        assertEquals(1, airing().path("todayAiringCount").asInt());
        assertEquals(other, anime.findById(id).orElseThrow().getBroadcastDay());
        assertEquals(today.minusMonths(6), anime.findById(id).orElseThrow().getEndDate());
    }

    @Test
    void 当已有关联编号不匹配但名称相同时应该拒绝按同名匹配() throws Exception {
        linked("同名作品", 101, today.getDayOfWeek().getValue());
        when(bangumi.getCalendar()).thenReturn(List.of(entry(102, "同名作品", today.getDayOfWeek().getValue())));
        assertEquals(0, airing().path("todayAiringCount").asInt());
    }

    @Test
    void 当没有关联编号但中文名称唯一匹配时应该列入当前参考() throws Exception {
        add("  唯一中文名  ", AnimeStatus.WATCHING, 1, 12, null, 0);
        when(bangumi.getCalendar()).thenReturn(List.of(Map.of("id", 101, "name", "Different title", "nameCn", "唯一中文名",
                "weekday", today.getDayOfWeek().getValue())));
        assertEquals(1, airing().path("todayAiringCount").asInt());
    }

    @Test
    void 当无编号名称对应多个日历作品时应该不猜匹配对象() throws Exception {
        add("同名作品", AnimeStatus.WATCHING, 1, 12, null, 0);
        when(bangumi.getCalendar()).thenReturn(List.of(entry(101, "同名作品", today.getDayOfWeek().getValue()),
                entry(102, "同名作品", today.getDayOfWeek().getValue())));
        assertEquals(0, airing().path("todayAiringCount").asInt());
    }

    @Test
    void 当日历显示未来开播而本地没有日期时应该排除今日参考() throws Exception {
        linked("未开播", 101, today.getDayOfWeek().getValue());
        when(bangumi.getCalendar()).thenReturn(List.of(Map.of("id", 101, "name", "未开播",
                "weekday", today.getDayOfWeek().getValue(), "airDate", today.plusDays(7).toString())));
        assertEquals(0, airing().path("todayAiringCount").asInt());
    }

    @Test
    void 当外部日历失败时应该说明未核实而不回退历史星期() throws Exception {
        linked("旧番", 101, today.getDayOfWeek().getValue());
        when(bangumi.getCalendar()).thenThrow(new IllegalStateException("上游不可用"));
        JsonNode result = airing();
        assertEquals(1, result.path("ongoingCount").asInt());
        assertEquals("UNAVAILABLE", result.path("airingStatus").asText());
        assertTrue(result.path("todayAiringCount").isNull()); assertTrue(result.path("todayAiring").isEmpty());
    }

    @Test
    void 当日历为空或缺少必要字段时应该说明无法核对() throws Exception {
        linked("旧番", 101, today.getDayOfWeek().getValue());
        when(bangumi.getCalendar()).thenReturn(List.of());
        assertEquals("UNAVAILABLE", airing().path("airingStatus").asText());
        when(bangumi.getCalendar()).thenReturn(List.of(Map.of("id", 101)));
        assertEquals("UNAVAILABLE", airing().path("airingStatus").asText());
    }

    @Test
    void 当核对放送日历时应该不占用数据库事务且不改写观看数据() throws Exception {
        Long id = linked("只读核对", 101, today.getDayOfWeek().getValue());
        record(id, today.minusDays(1), EpisodeRecordSource.LEGACY);
        var before = anime.findById(id).orElseThrow().getUpdatedAt();
        when(bangumi.getCalendar()).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return List.of(entry(101, "只读核对", today.getDayOfWeek().getValue()));
        });
        airing();
        assertEquals(before, anime.findById(id).orElseThrow().getUpdatedAt());
        assertEquals(1, records.count());
        assertEquals(EpisodeRecordSource.LEGACY, records.findByAnimeIdAndEpisodeNumber(id, 1).orElseThrow().getSource());
    }

    @Test
    void 当外部核对期间跨过午夜时应该不将昨日快照标为今日已核对() {
        DailyWatchService local = mock(DailyWatchService.class);
        LocalDate yesterday = today.minusDays(1);
        when(local.getContinuingSnapshot()).thenReturn(new DailyWatchDTO(yesterday,
                yesterday.getDayOfWeek().getValue(), 1, null, List.of(), List.of(), "UNVERIFIED"));
        when(bangumi.getCalendar()).thenReturn(List.of(entry(101, "跨日作品", yesterday.getDayOfWeek().getValue())));
        var result = new DailyAiringService(local, bangumi).getAiring();
        assertEquals("UNAVAILABLE", result.airingStatus());
        assertNull(result.todayAiringCount()); assertTrue(result.todayAiring().isEmpty());
        verify(bangumi).getCalendar();
    }

    private JsonNode daily() throws Exception {
        return mapper.readTree(mvc.perform(get("/api/watch/daily")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
    }

    private JsonNode airing() throws Exception {
        return mapper.readTree(mvc.perform(get("/api/watch/airing")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
    }

    private Map<String, Object> entry(int id, String name, int day) { return Map.of("id", id, "name", name, "weekday", day); }

    private Long linked(String name, int bangumiId, int day) {
        Long id = add(name, AnimeStatus.WATCHING, 1, 12, day, 0);
        Anime a = anime.findById(id).orElseThrow(); a.setBangumiId(bangumiId); anime.saveAndFlush(a); return id;
    }

    private Long add(String name, AnimeStatus status, Integer current, Integer total, Integer day, Integer order) {
        Anime a = new Anime(); a.setName(name); a.setStatus(status); a.setCurrentEpisode(current);
        a.setTotalEpisodes(total); a.setBroadcastDay(day); a.setSortOrder(order); return anime.saveAndFlush(a).getId();
    }

    private void record(Long id, LocalDate date, EpisodeRecordSource source) {
        EpisodeRecord r = new EpisodeRecord(); r.setAnimeId(id); r.setEpisodeNumber(1);
        r.setWatchedDate(date); r.setSource(source); records.saveAndFlush(r);
    }
}
