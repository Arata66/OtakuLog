package com.otakulog.service;

import com.otakulog.dto.BangumiEpisode;
import com.otakulog.dto.BangumiResult;
import com.otakulog.dto.BangumiSubjectDetail;

import java.util.List;
import java.util.Map;

// Bangumi API 服务接口
public interface BangumiService {
    List<BangumiResult> search(String keyword, int limit);

    BangumiSubjectDetail getSubject(int subjectId);

    List<BangumiEpisode> getSubjectEpisodes(int subjectId);

    List<Map<String, Object>> getCalendar();

    List<BangumiResult> getSubjectRankings(String sort, int limit, int offset);

    List<BangumiResult> getSeasonAnime(String sort, int limit, int offset);

    List<BangumiResult> searchByTag(String tag, int limit);

    List<Map<String, Object>> getUserCollections(String username, int limit);
}
