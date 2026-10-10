package com.otakulog.service;

import com.otakulog.dto.DailyWatchDTO;
import com.otakulog.dto.DailyWatchDTO.Item;
import com.otakulog.entity.Anime;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.enums.EpisodeRecordSource;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.EpisodeRecordRepository;
import com.otakulog.util.AnimeVOMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

@Service
public class DailyWatchService {
    private final AnimeRepository anime;
    private final EpisodeRecordRepository records;
    public DailyWatchService(AnimeRepository anime, EpisodeRecordRepository records) { this.anime = anime; this.records = records; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DailyWatchDTO getDaily() {
        var snapshot = getContinuingSnapshot();
        return new DailyWatchDTO(snapshot.date(), snapshot.weekday(), snapshot.ongoingCount(), null,
                snapshot.continueWatching().stream().limit(6).toList(), List.of(), "UNVERIFIED");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DailyWatchDTO getContinuingSnapshot() {
        LocalDate today = LocalDate.now();
        int weekday = today.getDayOfWeek().getValue();
        List<Anime> eligible = anime.findByStatus(AnimeStatus.WATCHING).stream()
                .filter(a -> a.getCurrentEpisode() != null && a.getCurrentEpisode() >= 0
                        && a.getTotalEpisodes() != null && a.getCurrentEpisode() < a.getTotalEpisodes()).toList();
        var dates = new HashMap<Long, LocalDate>();
        if (!eligible.isEmpty()) {
            for (Object[] row : records.findLatestKnownDates(eligible.stream().map(Anime::getId).toList(), today,
                    List.of(EpisodeRecordSource.WATCHED, EpisodeRecordSource.MANUAL, EpisodeRecordSource.IMPORT)))
                dates.put((Long) row[0], (LocalDate) row[1]);
        }
        Comparator<Item> order = Comparator.comparing(Item::lastWatchedDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(item -> item.anime().getSortOrder(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(item -> item.anime().getName())
                .thenComparing(item -> item.anime().getId());
        List<Item> continuing = eligible.stream().map(a -> new Item(AnimeVOMapper.toVO(a), dates.get(a.getId()))).sorted(order).toList();
        // 历史放送星期不能证明仍在播，核对交给独立入口且不占用此读取事务。
        return new DailyWatchDTO(today, weekday, continuing.size(), null, continuing, List.of(), "UNVERIFIED");
    }
}
