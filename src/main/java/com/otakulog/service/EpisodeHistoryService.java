package com.otakulog.service;

import com.otakulog.common.ResourceNotFoundException;
import com.otakulog.dto.EpisodeHistoryDTO;
import com.otakulog.dto.EpisodeHistoryDTO.Entry;
import com.otakulog.dto.EpisodeHistoryDTO.UpdateRequest;
import com.otakulog.entity.Anime;
import com.otakulog.entity.EpisodeRecord;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.EpisodeRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.HashMap;

@Service
public class EpisodeHistoryService {
    private final AnimeRepository anime;
    private final EpisodeRecordRepository records;
    private final WatchProgressService progress;

    public EpisodeHistoryService(AnimeRepository anime, EpisodeRecordRepository records, WatchProgressService progress) {
        this.anime = anime; this.records = records; this.progress = progress;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EpisodeHistoryDTO get(Long id, int page, int size) {
        if (page < 0 || size < 1 || size > 50) throw new IllegalArgumentException("页码必须非负，每页 1–50 条");
        Anime a = anime.findById(id).orElseThrow(() -> new ResourceNotFoundException("番剧不存在"));
        int current = a.getCurrentEpisode() == null ? 0 : Math.max(0, a.getCurrentEpisode());
        var existing = records.findByAnimeIdOrderByEpisodeNumberAsc(id);
        var byNumber = new HashMap<Integer, EpisodeRecord>();
        var extra = new ArrayList<EpisodeRecord>();
        for (EpisodeRecord r : existing) {
            byNumber.put(r.getEpisodeNumber(), r);
            if (r.getEpisodeNumber() > current) extra.add(r);
        }
        long total = (long) current + extra.size();
        var entries = new ArrayList<Entry>();
        // 按页计算集号，避免旧进度异常大时为全部缺失集分配内存。
        for (long index = (long) page * size; index < total && entries.size() < size; index++) {
            int number = index < current ? (int) index + 1 : extra.get((int) (index - current)).getEpisodeNumber();
            entries.add(Entry.from(number, byNumber.get(number)));
        }
        return new EpisodeHistoryDTO(id, current, page, size, total, entries);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void update(Long id, int number, UpdateRequest request) {
        // 与进退集及恢复使用同一番剧锁，拿锁后再读取用于比较的记录。
        Anime a = anime.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("番剧不存在"));
        progress.correctDate(a, number, request);
    }
}
