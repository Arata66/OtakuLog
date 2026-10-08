package com.otakulog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otakulog.entity.Anime;
import com.otakulog.entity.EpisodeRecord;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.enums.EpisodeRecordSource;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.EpisodeRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:annual_report;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AnnualReportServiceTest {
    @Autowired private AnnualReportService service;
    @Autowired private AnimeRepository anime;
    @Autowired private EpisodeRecordRepository episodes;
    @Autowired private MockMvc mvc;
    @MockBean private BangumiService bangumi;

    @BeforeEach
    void 清理隔离数据() { episodes.deleteAll(); anime.deleteAll(); }

    @Test
    void 当作品跨年完成且仍有追中作品时应该按逐集日期统计观看量() {
        Anime completed = item("跨年完成", AnimeStatus.FINISHED, "2026-02-01", 8.0, 12);
        Anime ongoing = item("仍在追中", AnimeStatus.WATCHING, null, 9.0, 2);
        record(completed, 1, "2025-12-31", EpisodeRecordSource.WATCHED);
        record(completed, 2, "2026-01-01", EpisodeRecordSource.WATCHED);
        record(ongoing, 1, "2026-03-01", EpisodeRecordSource.WATCHED);
        var report = service.getAnnualReport(2026);
        assertEquals(1, report.getTotalWatched()); assertEquals(2, report.getTotalEpisodes());
        assertEquals(0.8, report.getWatchingHours());
        assertEquals(2, json(2026).path("watchedAnimeCount").asLong());
        assertEquals(1L, ((Number) report.getMonthlyStats().get(0).get("episodes")).longValue());
        assertEquals(1, report.getMonthlyStats().get(1).get("count"));
    }

    @Test
    void 当同月完成的作品有未评分时应该从均分分母排除() {
        item("已评分", AnimeStatus.FINISHED, "2026-02-01", 8.0, 12);
        item("零代表未评分", AnimeStatus.FINISHED, "2026-02-02", 0.0, 12);
        item("空评分", AnimeStatus.FINISHED, "2026-02-03", null, 12);
        var report = service.getAnnualReport(2026);
        assertEquals(8.0, report.getAverageRating());
        assertEquals(8.0, report.getMonthlyStats().get(1).get("avgScore"));
        assertEquals(1, report.getMonthlyStats().get(1).get("ratedCount"));
        assertEquals(1, json(2026).path("ratedAnimeCount").asInt());
    }

    @Test
    void 当有历史估算与补录记录时应该分开确定日期与历史来源() {
        Anime target = item("来源区分", AnimeStatus.WATCHING, null, 0.0, 5);
        record(target, 1, "2026-01-01", EpisodeRecordSource.WATCHED);
        record(target, 2, "2026-01-02", EpisodeRecordSource.MANUAL);
        record(target, 3, "2026-01-03", EpisodeRecordSource.IMPORT);
        record(target, 4, "2026-01-04", EpisodeRecordSource.LEGACY);
        record(target, 5, null, EpisodeRecordSource.IMPORT);
        var report = json(2026);
        assertEquals(3, report.path("totalEpisodes").asLong());
        assertEquals(1, report.path("legacyDatedEpisodes").asLong());
        assertEquals(1, report.path("undatedEpisodeRecords").asLong());
        assertEquals(3, report.at("/monthlyStats/0/episodes").asLong());
        assertEquals(1, report.at("/monthlyStats/0/legacyEpisodes").asLong());
    }

    @Test
    void 当日期或逐集记录缺失时应该提示全库覆盖而不猜年份() {
        Anime target = item("日期未知", AnimeStatus.FINISHED, null, 8.0, 4);
        target.setCreatedAt(LocalDateTime.parse("2026-01-01T12:00:00")); anime.save(target);
        record(target, 1, null, EpisodeRecordSource.MANUAL);
        record(target, 2, "2025-01-01", EpisodeRecordSource.WATCHED);
        var report = json(2026);
        assertEquals(0, report.path("totalWatched").asLong());
        assertEquals(0, report.path("totalEpisodes").asLong());
        assertEquals(1, report.path("undatedEpisodeRecords").asLong());
        assertEquals(2, report.path("missingEpisodeRecords").asLong());
        assertEquals(1, report.path("undatedFinishedAnimeCount").asLong());
        assertEquals(1, json(2024).path("undatedEpisodeRecords").asLong());
    }

    @Test
    void 当年度数据为空时应该返回完整月份和明确估算说明() {
        var report = json(2026);
        assertEquals(0, report.path("totalEpisodes").asLong()); assertEquals(12, report.path("monthlyStats").size());
        assertEquals(24, report.path("minutesPerEpisode").asInt());
        assertTrue(report.path("watchingHoursEstimated").asBoolean());
        for (JsonNode month : report.path("monthlyStats")) {
            assertTrue(month.has("ratedCount")); assertTrue(month.has("episodes"));
            assertEquals(0, month.path("count").asInt()); assertEquals(0, month.path("avgScore").asDouble());
        }
    }

    @Test
    void 当观看发生在年界两侧时应该准确包含首尾当天() {
        Anime target = item("年界", AnimeStatus.DROPPED, null, 0.0, 4);
        record(target, 1, "2025-12-31", EpisodeRecordSource.WATCHED);
        record(target, 2, "2026-01-01", EpisodeRecordSource.WATCHED);
        record(target, 3, "2026-12-31", EpisodeRecordSource.WATCHED);
        record(target, 4, "2027-01-01", EpisodeRecordSource.WATCHED);
        assertEquals(2, service.getAnnualReport(2026).getTotalEpisodes());
        assertEquals(1, service.getAnnualReport(2025).getTotalEpisodes());
        assertEquals(1, json(2026).at("/monthlyStats/11/episodes").asInt());
    }

    @Test
    void 当完成发生在其他年份时应该仍计入当年实际观看() {
        Anime target = item("其他年完成", AnimeStatus.FINISHED, "2027-01-02", 9.0, 12);
        record(target, 1, "2026-12-31", EpisodeRecordSource.WATCHED);
        assertEquals(0, service.getAnnualReport(2026).getTotalWatched());
        assertEquals(1, service.getAnnualReport(2026).getTotalEpisodes());
        assertEquals(0, service.getAnnualReport(2026).getAverageRating());
    }

    @Test
    void 当存在越界评分时应该统一从均分和榜单排除() {
        item("有效", AnimeStatus.FINISHED, "2026-01-01", 8.0, 1);
        item("过大", AnimeStatus.FINISHED, "2026-01-01", 11.0, 1);
        item("负数", AnimeStatus.FINISHED, "2026-01-01", -1.0, 1);
        var report = service.getAnnualReport(2026);
        assertEquals(8, report.getAverageRating()); assertEquals(1, report.getTopAnimes().size());
        assertEquals(1, report.getRatingDistribution().stream().mapToInt(b -> (int) b.get("count")).sum());
    }

    @Test
    void 当请求非法年份时应该返回参数错误() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.getAnnualReport(0));
        assertThrows(IllegalArgumentException.class, () -> service.getAnnualReport(10000));
        mvc.perform(get("/api/report/annual/0").with(user("test"))).andExpect(status().isBadRequest());
    }

    @Test
    void 当通过API读取年报时应该返回解释字段且保持数据不变() throws Exception {
        Anime target = item("只读年报", AnimeStatus.WATCHING, null, 0.0, 1);
        record(target, 1, "2026-01-01", EpisodeRecordSource.WATCHED);
        var timestamp = anime.findById(target.getId()).orElseThrow().getUpdatedAt();
        mvc.perform(get("/api/report/annual/2026").with(user("test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalEpisodes").value(1))
                .andExpect(jsonPath("$.data.minutesPerEpisode").value(24))
                .andExpect(jsonPath("$.data.watchingHoursEstimated").value(true));
        assertEquals(1, episodes.count()); assertEquals(timestamp, anime.findById(target.getId()).orElseThrow().getUpdatedAt());
    }

    @Test
    void 当只有旧历史日期时应该单列历史数量且不回退整部集数() {
        Anime target = item("旧历史", AnimeStatus.FINISHED, "2026-01-01", 8.0, 12);
        record(target, 1, "2026-01-01", EpisodeRecordSource.LEGACY);
        var report = service.getAnnualReport(2026);
        assertEquals(1, report.getTotalWatched()); assertEquals(0, report.getTotalEpisodes());
        assertEquals(1, report.getLegacyDatedEpisodes()); assertEquals(0, report.getWatchingHours());
        assertEquals(11, report.getMissingEpisodeRecords());
    }

    @Test
    void 当记录缺少中间集号时应该按实际缺失数量提示() {
        Anime target = item("中间缺集", AnimeStatus.WATCHING, null, null, 4);
        record(target, 4, "2026-01-01", EpisodeRecordSource.WATCHED);
        assertEquals(3, service.getAnnualReport(2026).getMissingEpisodeRecords());
    }

    @Test
    void 当查询当前年和闰年时应该保持日期归属正确() {
        Anime target = item("闰年", AnimeStatus.WATCHING, null, 0.0, 1);
        record(target, 1, "2024-02-29", EpisodeRecordSource.MANUAL);
        assertEquals(1, json(2024).at("/monthlyStats/1/episodes").asInt());
        assertEquals(LocalDate.now().getYear(), service.getLatestAnnualReport().getYear());
    }

    private JsonNode json(int year) { return new ObjectMapper().valueToTree(service.getAnnualReport(year)); }

    private Anime item(String name, AnimeStatus status, String endDate, Double score, int progress) {
        Anime target = new Anime(); target.setName(name); target.setTotalEpisodes(12); target.setCurrentEpisode(progress);
        target.setStatus(status); target.setEndDate(endDate == null ? null : LocalDate.parse(endDate)); target.setScore(score);
        return anime.save(target);
    }

    private void record(Anime target, int number, String date, EpisodeRecordSource source) {
        EpisodeRecord record = new EpisodeRecord(); record.setAnimeId(target.getId()); record.setEpisodeNumber(number);
        record.setWatchedDate(date == null ? null : LocalDate.parse(date)); record.setSource(source); episodes.save(record);
    }
}
