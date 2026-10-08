package com.otakulog.util;

import com.otakulog.dto.AnimeVO;
import com.otakulog.entity.Anime;
import com.otakulog.entity.Tag;
import java.util.stream.Collectors;

public final class AnimeVOMapper {
    private AnimeVOMapper() {}

    public static AnimeVO toVO(Anime anime) {
        AnimeVO vo = new AnimeVO();
        vo.setId(anime.getId()); vo.setName(anime.getName());
        vo.setCurrentEpisode(anime.getCurrentEpisode()); vo.setTotalEpisodes(anime.getTotalEpisodes());
        vo.setStatus(anime.getStatus().name().toLowerCase()); vo.setStatusDisplay(anime.getStatus().getDisplayName());
        vo.setScore(anime.getScore()); vo.setSeason(anime.getSeason()); vo.setRemark(anime.getRemark());
        vo.setCoverUrl(anime.getCoverUrl());
        vo.setStartDate(anime.getStartDate() == null ? null : anime.getStartDate().toString());
        vo.setEndDate(anime.getEndDate() == null ? null : anime.getEndDate().toString());
        vo.setTags(anime.getTags() != null && !anime.getTags().isEmpty()
                ? anime.getTags().stream().map(Tag::getName).collect(Collectors.joining(",")) : null);
        vo.setSortOrder(anime.getSortOrder()); vo.setBroadcastDay(anime.getBroadcastDay()); vo.setBangumiId(anime.getBangumiId());
        vo.setWatchStartDate(anime.getWatchStartDate() == null ? null : anime.getWatchStartDate().toString());
        vo.setLegacy(anime.isLegacy()); vo.setWatchSeason(anime.getWatchSeason());
        if (anime.getTotalEpisodes() != null && anime.getTotalEpisodes() > 0 && anime.getCurrentEpisode() != null)
            vo.setProgress(Math.round((double) anime.getCurrentEpisode() / anime.getTotalEpisodes() * 1000.0) / 10.0);
        return vo;
    }
}
