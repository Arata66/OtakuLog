package com.otakulog.service;

import com.otakulog.entity.Anime;
import com.otakulog.entity.EpisodeRecord;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.enums.EpisodeRecordSource;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.EpisodeRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:bangumi_import;DB_CLOSE_DELAY=-1")
class BangumiImportTest {
    @Autowired private AnimeService service;
    @Autowired private AnimeRepository anime;
    @SpyBean private EpisodeRecordRepository records;
    @MockBean private BangumiService bangumi;
    @BeforeEach
    void 清理隔离数据() { records.deleteAll(); anime.deleteAll(); }
    @Test
    void 当集数未知或状态不可映射时应该提示需核对而不是猜集数或追中() {
        when(bangumi.getUserCollections("test", 200)).thenReturn(List.of(row(1, 0, 2, 3), row(2, 12, 2, 4), row(3, 12, 2, 3)));
        var result = service.importFromBangumi("test");
        assertEquals(1, result.get("created")); assertEquals(2, result.get("skipped")); assertEquals(2, result.get("needsReview"));
        assertEquals(1, anime.count()); assertEquals(2, records.count());
    }
    @Test
    void 当上游进度非法时应该跳过需核对记录并保留有效记录() {
        when(bangumi.getUserCollections("test", 200)).thenReturn(List.of(row(1, 12, 13, 3), row(2, 12, 1, 1), row(3, 12, 2, 3)));
        var result = service.importFromBangumi("test");
        assertEquals(1, result.get("created")); assertEquals(2, result.get("needsReview")); assertEquals(1, anime.count());
    }
    @Test
    void 当读取远程收藏时应该在数据库事务外执行且失败不写入() {
        when(bangumi.getUserCollections("test", 200)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("模拟上游失败");
        });
        assertThrows(IllegalStateException.class, () -> service.importFromBangumi("test"));
        assertEquals(0, anime.count()); assertEquals(0, records.count());
    }
    @Test
    void 当有效收藏包含完成和追中作品时应该保留未知日期且重复导入不新增() {
        when(bangumi.getUserCollections("test", 200)).thenReturn(List.of(row(1, 3, 0, 2), row(2, 12, 2, 3)));
        assertEquals(2, service.importFromBangumi("test").get("created"));
        assertEquals(2, service.importFromBangumi("test").get("skipped"));
        assertEquals(2, anime.count()); assertEquals(5, records.count());
        assertTrue(records.findAll().stream().allMatch(r -> r.getWatchedDate() == null && r.getSource() == EpisodeRecordSource.IMPORT));
        assertTrue(anime.findAll().stream().allMatch(a -> a.getEndDate() == null));
        assertEquals(0, service.getHeatmap().values().stream().mapToInt(Integer::intValue).sum());
    }
    @Test
    void 当已有同名作品时应该保留本地资料进度和逐集来源() {
        Anime existing = new Anime(); existing.setName("导入样例1"); existing.setTotalEpisodes(12);
        existing.setCurrentEpisode(1); existing.setStatus(AnimeStatus.WATCHING); existing.setRemark("本地资料");
        existing = anime.saveAndFlush(existing);
        EpisodeRecord record = new EpisodeRecord(); record.setAnimeId(existing.getId()); record.setEpisodeNumber(1);
        record.setWatchedDate(LocalDate.of(2024, 1, 1)); record.setSource(EpisodeRecordSource.MANUAL); records.saveAndFlush(record);
        when(bangumi.getUserCollections("test", 200)).thenReturn(List.of(row(1, 12, 9, 3)));
        assertEquals(1, service.importFromBangumi("test").get("skipped"));
        assertEquals("本地资料", anime.findById(existing.getId()).orElseThrow().getRemark());
        assertEquals(1, anime.findById(existing.getId()).orElseThrow().getCurrentEpisode());
        assertEquals(EpisodeRecordSource.MANUAL, records.findAll().get(0).getSource());
    }
    @Test
    void 当写入后续收藏失败时应该整批回滚作品和逐集记录() {
        when(bangumi.getUserCollections("test", 200)).thenReturn(List.of(row(1, 12, 2, 3), row(2, 12, 2, 3)));
        doAnswer(call -> {
            EpisodeRecord record = call.getArgument(0);
            if (records.count() >= 2) throw new IllegalStateException("模拟中途写入失败");
            return call.callRealMethod();
        }).when(records).save(any(EpisodeRecord.class));
        assertThrows(RuntimeException.class, () -> service.importFromBangumi("test"));
        assertEquals(0, anime.count()); assertEquals(0, records.count());
    }
    @Test
    void 当收藏集数异常庞大时应该拒绝整批导入而不是生成大量记录() {
        when(bangumi.getUserCollections("test", 200)).thenReturn(List.of(row(1, 12, 2, 3), row(2, Integer.MAX_VALUE, 0, 2)));
        assertThrows(IllegalArgumentException.class, () -> service.importFromBangumi("test"));
        assertEquals(0, anime.count()); assertEquals(0, records.count());
    }
    private Map<String, Object> row(int id, int eps, int progress, int type) {
        return Map.of("subjectId", id, "name", "导入样例" + id, "eps", eps, "epStatus", progress, "type", type);
    }
}
