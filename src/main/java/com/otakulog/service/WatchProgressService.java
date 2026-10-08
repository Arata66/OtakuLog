package com.otakulog.service;

import com.otakulog.entity.Anime;
import com.otakulog.common.ConflictException;
import com.otakulog.dto.EpisodeHistoryDTO.Entry;
import com.otakulog.dto.EpisodeHistoryDTO.UpdateRequest;
import com.otakulog.entity.EpisodeRecord;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.enums.EpisodeRecordSource;
import com.otakulog.repository.EpisodeRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class WatchProgressService {

    private final EpisodeRecordRepository records;

    public WatchProgressService(EpisodeRecordRepository records) {
        this.records = records;
    }

    public void correctDate(Anime anime, int number, UpdateRequest request) {
        Entry expected = request == null ? null : request.expected();
        if (number < 1 || expected == null || expected.recorded() == null
                || !Objects.equals(expected.episodeNumber(), number))
            throw new IllegalArgumentException("必须提供该集的完整原记录快照");
        if (expected.recorded() && (expected.recordId() == null || expected.source() == null)
                || !expected.recorded() && (expected.recordId() != null || expected.source() != null
                || expected.watchedDate() != null || expected.updatedAt() != null))
            throw new IllegalArgumentException("原记录快照不完整");
        LocalDate date = request.watchedDate();
        if (date != null && (date.getYear() < 1000 || date.getYear() > 9999 || date.isAfter(LocalDate.now())))
            throw new IllegalArgumentException("观看日期应为 1000 年起的已发生日期，未知请留空");
        EpisodeRecord record = records.findByAnimeIdAndEpisodeNumber(anime.getId(), number).orElse(null);
        if (!Entry.from(number, record).equals(expected))
            throw new ConflictException("该集记录已变化，请重新加载后核对，未覆盖当前记录");
        if (record == null) {
            if (number > current(anime)) throw new IllegalArgumentException("不能补录当前进度之外的未观看集");
            record = new EpisodeRecord(); record.setAnimeId(anime.getId()); record.setEpisodeNumber(number);
        } else if (record.getSource() != EpisodeRecordSource.LEGACY && Objects.equals(record.getWatchedDate(), date)) {
            return;
        }
        // 只有主动保存的这一集视为已核对，其他历史来源保持不变。
        record.setWatchedDate(date); record.setSource(EpisodeRecordSource.MANUAL);
        records.saveAndFlush(record);
    }

    public void initialize(Anime anime, boolean suppliedWatchDate) {
        int total = total(anime);
        if (anime.getStatus() == AnimeStatus.FINISHED) {
            anime.setCurrentEpisode(total);
            if (anime.getEndDate() == null && !anime.isLegacy()) anime.setEndDate(LocalDate.now());
            backfill(anime, EpisodeRecordSource.MANUAL);
        } else if (anime.getStatus() == AnimeStatus.WATCHING) {
            anime.setCurrentEpisode(1);
            LocalDate date = anime.getWatchStartDate();
            record(anime, 1, date, suppliedWatchDate || anime.isLegacy()
                    ? EpisodeRecordSource.MANUAL : EpisodeRecordSource.WATCHED);
            if (total == 1) {
                anime.setStatus(AnimeStatus.FINISHED);
                if (anime.getEndDate() == null) anime.setEndDate(date);
            } else {
                anime.setEndDate(null);
            }
        } else {
            anime.setCurrentEpisode(0);
            anime.setEndDate(null);
        }
    }

    public void next(Anime anime) {
        int episode = current(anime) + 1;
        if (episode > total(anime)) throw new IllegalArgumentException("reached_max");
        anime.setCurrentEpisode(episode);
        if (anime.getWatchStartDate() == null) anime.setWatchStartDate(LocalDate.now());
        record(anime, episode, LocalDate.now(), EpisodeRecordSource.WATCHED);
        anime.setStatus(episode == total(anime) ? AnimeStatus.FINISHED : AnimeStatus.WATCHING);
        anime.setEndDate(episode == total(anime) ? LocalDate.now() : null);
    }

    public void previous(Anime anime) {
        int episode = current(anime);
        if (episode <= 0) throw new IllegalArgumentException("reached_min");
        records.deleteByAnimeIdAndEpisodeNumber(anime.getId(), episode);
        anime.setCurrentEpisode(episode - 1);
        anime.setStatus(episode == 1 ? AnimeStatus.PLANNING : AnimeStatus.WATCHING);
        anime.setEndDate(null);
    }

    public void changeStatus(Anime anime, AnimeStatus status) {
        if (status == null) throw new IllegalArgumentException("状态不能为空");
        if (status == AnimeStatus.PLANNING && current(anime) > 0) {
            throw new IllegalArgumentException("已有观看进度，请先退回第 0 集再改为计划");
        }
        if (status == AnimeStatus.WATCHING && current(anime) == 0) {
            next(anime);
            return;
        }
        AnimeStatus previous = anime.getStatus();
        anime.setStatus(status);
        if (status == AnimeStatus.FINISHED) {
            anime.setCurrentEpisode(total(anime));
            if (anime.getEndDate() == null && previous != AnimeStatus.FINISHED) anime.setEndDate(LocalDate.now());
            backfill(anime, EpisodeRecordSource.MANUAL);
        } else {
            anime.setEndDate(null);
        }
    }

    public void restore(Anime anime, int progress, AnimeStatus status, LocalDate endDate) {
        int restored = status == AnimeStatus.FINISHED ? total(anime) : progress;
        if (restored < 0 || restored > total(anime)) throw new IllegalArgumentException("导入集数超出范围");
        if (restored < current(anime)) throw new IllegalArgumentException("导入进度不能低于已有进度");
        if (status == AnimeStatus.PLANNING && restored > 0) throw new IllegalArgumentException("计划状态不能包含观看进度");
        anime.setCurrentEpisode(restored);
        anime.setStatus(status);
        if (status == AnimeStatus.FINISHED) {
            if (endDate != null) anime.setEndDate(endDate);
        } else {
            anime.setEndDate(null);
        }
        backfill(anime, EpisodeRecordSource.IMPORT);
    }

    private void backfill(Anime anime, EpisodeRecordSource source) {
        // 完成或导入只证明进度，缺失的逐集日期不能猜成操作日期。
        for (int episode = 1; episode <= current(anime); episode++) record(anime, episode, null, source);
    }

    private void record(Anime anime, int episode, LocalDate date, EpisodeRecordSource source) {
        if (records.findByAnimeIdAndEpisodeNumber(anime.getId(), episode).isPresent()) return;
        EpisodeRecord record = new EpisodeRecord();
        record.setAnimeId(anime.getId());
        record.setEpisodeNumber(episode);
        record.setWatchedDate(date);
        record.setSource(source);
        records.save(record);
    }

    private int total(Anime anime) {
        if (anime.getTotalEpisodes() == null || anime.getTotalEpisodes() < 1) {
            throw new IllegalArgumentException("总集数必须大于 0");
        }
        return anime.getTotalEpisodes();
    }

    private int current(Anime anime) {
        return anime.getCurrentEpisode() == null ? 0 : anime.getCurrentEpisode();
    }
}
