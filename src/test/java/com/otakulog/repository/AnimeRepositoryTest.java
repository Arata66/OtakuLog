package com.otakulog.repository;

import com.otakulog.entity.Anime;
import com.otakulog.enums.AnimeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class AnimeRepositoryTest {

    @Autowired
    private AnimeRepository animeRepository;

    @BeforeEach
    void setUp() {
        animeRepository.deleteAll();
    }

    private Anime createAnime(String name, AnimeStatus status, int eps) {
        Anime a = new Anime();
        a.setName(name);
        a.setStatus(status);
        a.setCurrentEpisode(eps > 0 ? 1 : 0);
        a.setTotalEpisodes(eps);
        a.setScore(8.0);
        a.setRemark("");
        a.setWatchStartDate(LocalDate.now());
        return a;
    }

    @Test
    void existsByName_shouldReturnTrue() {
        animeRepository.save(createAnime("葬送的芙莉莲", AnimeStatus.WATCHING, 28));
        assertTrue(animeRepository.existsByName("葬送的芙莉莲"));
        assertFalse(animeRepository.existsByName("不存在"));
    }

    @Test
    void existsByBangumiId_shouldReturnTrue() {
        Anime a = createAnime("测试番", AnimeStatus.WATCHING, 12);
        a.setBangumiId(12345);
        animeRepository.save(a);
        assertTrue(animeRepository.existsByBangumiId(12345));
        assertFalse(animeRepository.existsByBangumiId(99999));
    }

    @Test
    void findByStatus_shouldFilterCorrectly() {
        animeRepository.save(createAnime("追中番", AnimeStatus.WATCHING, 12));
        animeRepository.save(createAnime("已完成番", AnimeStatus.FINISHED, 24));

        assertEquals(1, animeRepository.findByStatus(AnimeStatus.WATCHING).size());
        assertEquals(1, animeRepository.findByStatus(AnimeStatus.FINISHED).size());
    }

    @Test
    void findByNameContaining_shouldPartialMatch() {
        animeRepository.save(createAnime("进击的巨人", AnimeStatus.FINISHED, 87));
        animeRepository.save(createAnime("间谍过家家", AnimeStatus.WATCHING, 25));

        List<Anime> results = animeRepository.findByNameContaining("巨人");
        assertEquals(1, results.size());
        assertEquals("进击的巨人", results.get(0).getName());
    }

    @Test
    void countByStatus_shouldReturnCount() {
        animeRepository.save(createAnime("番1", AnimeStatus.WATCHING, 12));
        animeRepository.save(createAnime("番2", AnimeStatus.WATCHING, 24));
        animeRepository.save(createAnime("番3", AnimeStatus.FINISHED, 12));

        assertEquals(2L, animeRepository.countByStatus(AnimeStatus.WATCHING));
        assertEquals(1L, animeRepository.countByStatus(AnimeStatus.FINISHED));
    }

    @Test
    void getAggregatedStats_shouldReturnMergedStats() {
        animeRepository.save(createAnime("番1", AnimeStatus.WATCHING, 12));
        animeRepository.save(createAnime("番2", AnimeStatus.FINISHED, 24));

        List<Object[]> rows = animeRepository.getAggregatedStats();
        assertTrue(rows.size() > 0);

        Object[] row = rows.get(0);
        assertEquals(2L, row[0]);
        assertEquals(1L, row[1]);
        assertEquals(1L, row[2]);
    }

    @Test
    void findByTagContaining_shouldMatchTags() {
        Anime a = createAnime("热血番", AnimeStatus.WATCHING, 12);
        a.setTags("热血,战斗");
        animeRepository.save(a);

        List<Anime> results = animeRepository.findByTagContaining("热血", PageRequest.of(0, 10))
                .getContent();
        assertEquals(1, results.size());
    }

    @Test
    void findByBroadcastDay_shouldFilterByDay() {
        Anime a1 = createAnime("周一番", AnimeStatus.WATCHING, 12);
        a1.setBroadcastDay(1);
        Anime a2 = createAnime("周三番", AnimeStatus.WATCHING, 12);
        a2.setBroadcastDay(3);
        animeRepository.saveAll(List.of(a1, a2));

        var mondayAnime = animeRepository.findByBroadcastDay(1, Sort.by("name"));
        assertEquals(1, mondayAnime.size());
        assertEquals("周一番", mondayAnime.get(0).getName());
    }
}
