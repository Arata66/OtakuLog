package com.otakulog.service;

import com.otakulog.entity.Anime;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.repository.AnimeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@Service
public class BangumiImportService {
    private final BangumiService bangumi;
    private final AnimeRepository anime;
    private final WatchProgressService watchProgress;
    private final TransactionTemplate transaction;

    public BangumiImportService(BangumiService bangumi, AnimeRepository anime,
                               WatchProgressService watchProgress, PlatformTransactionManager manager) {
        this.bangumi = bangumi;
        this.anime = anime;
        this.watchProgress = watchProgress;
        this.transaction = new TransactionTemplate(manager);
    }

    public Map<String, Object> importCollections(String username) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("请输入 Bangumi 账号标识");
        // 网络失败发生在写入前，避免分页未完成时导入部分收藏或长时间占用事务。
        List<Map<String, Object>> collections = bangumi.getUserCollections(username.strip(), 200);
        return transaction.execute(status -> importRows(collections));
    }

    private Map<String, Object> importRows(List<Map<String, Object>> collections) {
        int created = 0, skipped = 0, needsReview = 0;
        long recordCount = 0;
        for (Map<String, Object> item : collections) {
            Integer id = integer(item.get("subjectId"));
            String name = string(item.get("nameCn"));
            if (name.isEmpty()) name = string(item.get("name"));
            if ((id != null && anime.existsByBangumiId(id)) || (!name.isEmpty() && anime.existsByName(name))) {
                skipped++;
                continue;
            }
            Integer total = integer(item.get("eps")), progress = integer(item.get("epStatus")), type = integer(item.get("type"));
            AnimeStatus status = type == null ? null : switch (type) {
                case 1 -> AnimeStatus.PLANNING;
                case 2 -> AnimeStatus.FINISHED;
                case 3 -> AnimeStatus.WATCHING;
                case 5 -> AnimeStatus.DROPPED;
                default -> null;
            };
            // 本地没有搁置状态；未知集数和矛盾进度必须由用户核对，不能猜测。
            if (id == null || id <= 0 || name.isEmpty() || name.length() > 255 || total == null || total <= 0
                    || status == null || progress == null || progress < 0 || progress > total
                    || (status == AnimeStatus.PLANNING && progress != 0)) {
                skipped++; needsReview++;
                continue;
            }
            recordCount += status == AnimeStatus.FINISHED ? total : progress;
            if (recordCount > 100_000) throw new IllegalArgumentException("本次收藏导入逐集记录超过 100000 条，请先核对集数和进度");
            Anime value = new Anime();
            value.setName(name); value.setBangumiId(id); value.setTotalEpisodes(total);
            value.setCurrentEpisode(0); value.setStatus(status); value.setCoverUrl(string(item.get("image")));
            value.setSeason(season(item.get("date"))); value.setScore(0.0); value.setRemark("");
            watchProgress.restore(anime.save(value), progress, status, null);
            created++;
        }
        return Map.of("created", created, "skipped", skipped, "needsReview", needsReview, "total", collections.size());
    }

    private Integer integer(Object value) {
        if (!(value instanceof Number number)) return null;
        double d = number.doubleValue();
        return Double.isFinite(d) && d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE ? (int) d : null;
    }

    private String string(Object value) { return value instanceof String s ? s.strip() : ""; }

    private String season(Object value) {
        try {
            LocalDate date = LocalDate.parse(string(value));
            return date.getYear() + new String[]{"冬", "春", "夏", "秋"}[(date.getMonthValue() - 1) / 3];
        } catch (DateTimeParseException e) { return ""; }
    }
}
