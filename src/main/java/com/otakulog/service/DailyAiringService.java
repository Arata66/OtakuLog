package com.otakulog.service;

import com.otakulog.dto.AnimeVO;
import com.otakulog.dto.DailyWatchDTO;
import com.otakulog.dto.DailyWatchDTO.Item;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class DailyAiringService {
    private final DailyWatchService daily;
    private final BangumiService bangumi;

    public DailyAiringService(DailyWatchService daily, BangumiService bangumi) { this.daily = daily; this.bangumi = bangumi; }

    public DailyWatchDTO getAiring() {
        var snapshot = daily.getContinuingSnapshot();
        if (snapshot.ongoingCount() == 0) return result(snapshot, List.of(), "VERIFIED");
        List<Map<String, Object>> calendar;
        try {
            // 外部核对放在本地快照事务结束之后，不调用会写回放送日的日历服务。
            calendar = bangumi.getCalendar();
        } catch (RuntimeException e) {
            log.warn("当前放送日历核对失败");
            return result(snapshot, List.of(), "UNAVAILABLE");
        }
        if (calendar == null || calendar.isEmpty() || calendar.stream().anyMatch(item -> item == null
                || !validNumber(item.get("id"), 1, Integer.MAX_VALUE) || !validNumber(item.get("weekday"), 1, 7)))
            return result(snapshot, List.of(), "UNAVAILABLE");
        List<Item> airing = snapshot.continueWatching().stream().filter(item -> {
            AnimeVO anime = item.anime();
            var match = match(anime, calendar);
            return match != null && ((Number) match.get("weekday")).intValue() == snapshot.weekday()
                    && started(anime.getStartDate(), snapshot.date()) && started(match.get("airDate"), snapshot.date());
        }).toList();
        return result(snapshot, airing, "VERIFIED");
    }

    private DailyWatchDTO result(DailyWatchDTO snapshot, List<Item> airing, String status) {
        // 外部请求可能跨午夜，昨日快照不能再被确认为今日参考。
        if (!snapshot.date().equals(LocalDate.now())) { status = "UNAVAILABLE"; airing = List.of(); }
        return new DailyWatchDTO(snapshot.date(), snapshot.weekday(), snapshot.ongoingCount(),
                "VERIFIED".equals(status) ? airing.size() : null, snapshot.continueWatching().stream().limit(6).toList(),
                airing.stream().limit(6).toList(), status);
    }

    private Map<String, Object> match(AnimeVO anime, List<Map<String, Object>> calendar) {
        List<Map<String, Object>> matches;
        if (anime.getBangumiId() != null) {
            matches = calendar.stream().filter(item -> ((Number) item.get("id")).intValue() == anime.getBangumiId()).toList();
        } else {
            String name = normalized(anime.getName());
            if (name.isEmpty()) return null;
            matches = calendar.stream().filter(item -> name.equals(normalized(item.get("name")))
                    || name.equals(normalized(item.get("nameCn")))).toList();
        }
        // 同名或重复日历条目都不能让本地作品被误认成另一部正在播的作品。
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private String normalized(Object name) { return name instanceof String text ? text.strip().toLowerCase(Locale.ROOT) : ""; }

    private boolean validNumber(Object value, int min, int max) {
        return value instanceof Number number && number.doubleValue() >= min && number.doubleValue() <= max
                && number.doubleValue() == number.intValue();
    }

    private boolean started(Object date, LocalDate today) {
        if (date == null || date instanceof String text && text.isBlank()) return true;
        if (!(date instanceof String text)) return false;
        try { return !LocalDate.parse(text).isAfter(today); }
        catch (DateTimeParseException e) { return false; }
    }
}
