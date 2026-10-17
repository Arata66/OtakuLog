package com.otakulog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.otakulog.common.*;
import com.otakulog.dto.BangumiImportDTO.*;
import com.otakulog.entity.*;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.repository.*;
import org.springframework.data.domain.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class BangumiImportPreviewService {
    private final BangumiService bangumi;
    private final AnimeRepository anime;
    private final ExternalCollectionRepository collections;
    private final ImportBatchRepository batches;
    private final ImportLockRepository locks;
    private final TagRepository tags;
    private final WatchProgressService watch;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private Clock clock = Clock.systemDefaultZone();
    private final Cache<String, Pending> previews = Caffeine.newBuilder().maximumSize(20)
            .expireAfterWrite(Duration.ofMinutes(15)).build();

    public BangumiImportPreviewService(BangumiService bangumi, AnimeRepository anime, ExternalCollectionRepository collections,
            ImportBatchRepository batches, ImportLockRepository locks, TagRepository tags, WatchProgressService watch,
            PlatformTransactionManager manager, ObjectMapper mapper,
            @Value("${app.import.transaction-isolation:ISOLATION_SERIALIZABLE}") String isolation) {
        this.bangumi = bangumi; this.anime = anime; this.collections = collections; this.batches = batches;
        this.locks = locks; this.tags = tags; this.watch = watch; this.mapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevelName(isolation);
    }

    public Preview preview(String owner, String username) {
        owner(owner); username = account(username).toLowerCase(Locale.ROOT);
        var snapshot = bangumi.getCompleteUserCollections(username);
        if (snapshot == null) throw new ExternalApiException("收藏读取失败，请重试");
        var sources = snapshot.rows().stream().map(this::source).toList();
        String account = username;
        return transaction.execute(status -> {
            lock();
            var local = lockedAnime();
            var rows = classify(sources, local);
            String key = UUID.randomUUID().toString();
            Instant expires = clock.instant().plus(Duration.ofMinutes(15));
            var preview = new Preview(key, account, snapshot.total(), sources.size(), true, snapshot.sourceChanged(), expires,
                    count(rows, "NEW"), count(rows, "EXISTING"), count(rows, "REVIEW"), rows);
            previews.put(key, new Pending(owner, preview, fingerprint(local, account)));
            return preview;
        });
    }

    public Result confirm(String owner, ConfirmRequest request) {
        if (request == null) throw new IllegalArgumentException("确认内容不能为空");
        uuid(request.previewKey()); uuid(request.requestKey()); owner(owner);
        String options = hash(request.previewKey() + ":" + request.importScores() + ":" + request.importTags());
        return transaction.execute(status -> {
            lock();
            var previous = batches.findByRequestKey(request.requestKey()).orElse(null);
            if (previous != null) {
                if (!previous.getOwner().equals(owner) || !previous.getFingerprint().equals(options))
                    throw new ConflictException("同一导入请求的归属或选项不同，请保留原结果后重新预览");
                return read(previous.getResultJson(), Result.class);
            }
            Pending pending = previews.getIfPresent(request.previewKey());
            if (pending == null || !pending.owner.equals(owner) || !clock.instant().isBefore(pending.preview.expiresAt()))
                throw new ResourceNotFoundException("预览不存在或已过期，请重新读取收藏");
            var local = lockedAnime();
            if (!pending.fingerprint.equals(fingerprint(local, pending.preview.username())))
                throw new ConflictException("本地作品或外部收藏已变化，请重新预览后确认；未导入任何条目");
            var existingIds = new HashSet<>(collections.findByUsernameOrderBySubjectId(pending.preview.username())
                    .stream().map(ExternalCollection::getSubjectId).toList());
            long added = pending.preview.rows().stream().filter(row -> !existingIds.contains(row.source().subjectId())).count();
            if (collections.count() + added > 100000 || batches.count() >= 100000)
                throw new IllegalArgumentException("外部收藏或导入历史已达到100000条保护上限，请先备份并核对");
            int created = 0;
            long recordCount = 0;
            LocalDateTime captured = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
            for (var row : pending.preview.rows()) {
                var source = row.source();
                var external = collections.findByUsernameAndSubjectId(pending.preview.username(), source.subjectId()).orElse(null);
                if (external == null) {
                    external = new ExternalCollection(); external.setCollectionKey(UUID.randomUUID().toString());
                    external.setUsername(pending.preview.username()); external.setSubjectId(source.subjectId());
                } else {
                    if (external.getVersion() == Long.MAX_VALUE) throw new IllegalArgumentException("外部收藏版本已达上限，请先核对备份");
                    external.setVersion(external.getVersion() + 1);
                }
                external.setPayload(json(source)); external.setCollectionType(source.type()); external.setCapturedAt(captured);
                if (external.getPayload().getBytes(StandardCharsets.UTF_8).length > 64000) throw new IllegalArgumentException("外部收藏原文过长，未执行导入");
                if (external.getCreatedAt() == null) external.setCreatedAt(captured);
                external.setUpdatedAt(captured); collections.save(external);
                if (!row.result().equals("NEW")) continue;
                AnimeStatus state = state(source.type());
                recordCount += state == AnimeStatus.FINISHED ? source.eps() : source.epStatus();
                if (recordCount > 100000) throw new IllegalArgumentException("本次逐集记录超过100000条，请核对收藏资料");
                var value = new Anime(); value.setBangumiId(source.subjectId()); value.setName(title(source));
                value.setTotalEpisodes(source.eps()); value.setCurrentEpisode(0); value.setStatus(state);
                value.setScore(request.importScores() && source.rate() != null ? source.rate().doubleValue() : 0.0);
                value.setRemark(""); value.setCoverUrl(source.image()); value.setSeason(season(source.date()));
                if (request.importTags()) for (String raw : source.tags()) {
                    String name = raw.strip();
                    if (name.isEmpty() || name.length() > 50) continue;
                    value.getTags().add(tags.findByName(name).orElseGet(() -> tags.save(new Tag(name))));
                }
                watch.restore(anime.save(value), source.epStatus(), state, null); created++;
            }
            var result = new Result(request.requestKey(), created, pending.preview.readCount() - created,
                    pending.preview.reviewCount(), pending.preview.readCount(), pending.preview.readCount());
            var batch = new ImportBatch(); batch.setRequestKey(request.requestKey()); batch.setOwner(owner);
            batch.setUsername(pending.preview.username()); batch.setFingerprint(options); batch.setResultJson(json(result));
            batch.setCreatedAt(captured); batch.setUpdatedAt(captured);
            batches.saveAndFlush(batch);
            return result;
        });
    }

    public Map<String, Object> list(int page, int size) {
        if (page < 0 || size < 1 || size > 50) throw new IllegalArgumentException("外部收藏分页参数无效");
        var result = collections.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "capturedAt", "id")));
        return Map.of("page", page, "total", result.getTotalElements(), "entries", result.stream().map(e -> new ExternalRecord(
                e.getCollectionKey(), e.getUsername(), read(e.getPayload(), CollectionData.class), e.getVersion(), e.getCapturedAt())).toList());
    }

    public void remove(String key, long expectedVersion) {
        uuid(key);
        transaction.executeWithoutResult(status -> {
            lock();
            var value = collections.findByCollectionKey(key).orElseThrow(() -> new ResourceNotFoundException("外部收藏不存在"));
            if (value.getVersion() != expectedVersion) throw new ConflictException("外部收藏已变化，请刷新后核对");
            collections.delete(value);
        });
    }

    private void lock() {
        locks.lockSingleton().orElseThrow(() -> new IllegalStateException("导入锁缺失，请完成数据库迁移"));
    }
    private List<Anime> lockedAnime() {
        return anime.findAllIdsForRestore().stream().map(id -> anime.findByIdForUpdate(id).orElseThrow()).toList();
    }
    private String fingerprint(List<Anime> local, String username) {
        var rows = local.stream().map(a -> Arrays.asList(a.getId(), a.getName(), a.getBangumiId(), a.getCurrentEpisode(),
                a.getTotalEpisodes(), a.getStatus(), a.getScore(), a.getUpdatedAt(), a.getRemark(), a.getCoverUrl(),
                a.getSeason(), a.getStartDate(), a.getEndDate(), a.getSortOrder(), a.getBroadcastDay(),
                a.getWatchStartDate(), a.isLegacy(), a.getWatchSeason(),
                a.getTags().stream().map(Tag::getName).sorted().toList())).toList();
        var external = collections.findByUsernameOrderBySubjectId(username).stream()
                .map(e -> Arrays.asList(e.getCollectionKey(), e.getVersion(), e.getPayload(), e.getCapturedAt())).toList();
        return hash(json(List.of(rows, external)));
    }
    private List<Row> classify(List<CollectionData> sources, List<Anime> local) {
        var result = new ArrayList<Row>(); var names = new HashSet<String>();
        for (var source : sources) {
            String name = title(source);
            var matches = local.stream().filter(a -> Objects.equals(a.getBangumiId(), source.subjectId())
                    || a.getName().equalsIgnoreCase(name)).toList();
            String reason = reviewReason(source);
            if (matches.size() > 1 || (matches.size() == 1 && matches.get(0).getBangumiId() != null
                    && !matches.get(0).getBangumiId().equals(source.subjectId()))) {
                result.add(new Row(source, "REVIEW", "作品名称与编号冲突，只保留外部收藏"));
            } else if (!matches.isEmpty()) result.add(new Row(source, "EXISTING", "已有作品保留，不覆盖资料、评分或观看进度"));
            else if (reason != null) result.add(new Row(source, "REVIEW", reason));
            else if (!names.add(name.toLowerCase(Locale.ROOT))) result.add(new Row(source, "REVIEW", "本批存在同名作品，只保留外部收藏"));
            else result.add(new Row(source, "NEW", source.tags().stream().anyMatch(t -> t.strip().length() > 50)
                    ? "可新增；超过50字的收藏标签只保留在外部原始资料" : "可新增；未知观看日期保持为空"));
        }
        return List.copyOf(result);
    }
    private String reviewReason(CollectionData source) {
        if (title(source).isBlank() || title(source).length() > 255) return "缺少有效名称，只保留外部收藏";
        if (state(source.type()) == null) return "搁置或未知状态没有本地对应，只保留外部收藏";
        if (source.eps() == null || source.eps() <= 0) return "集数未知，只保留外部收藏";
        if (source.epStatus() == null || source.epStatus() < 0 || source.epStatus() > source.eps()
                || (source.type() == 1 && source.epStatus() != 0)) return "观看进度与资料矛盾，只保留外部收藏";
        return null;
    }
    private CollectionData source(Map<String, Object> row) {
        List<String> sourceTags = new ArrayList<>();
        if (row.get("tags") != null) {
            if (!(row.get("tags") instanceof List<?> values)) throw new ExternalApiException("收藏标签格式异常");
            for (Object value : values) {
                if (!(value instanceof String text)) throw new ExternalApiException("收藏标签格式异常");
                sourceTags.add(text);
            }
        }
        var data = new CollectionData(number(row.get("subjectId")), text(row.get("name")), text(row.get("nameCn")),
                number(row.get("type")), number(row.get("eps")), number(row.get("epStatus")), number(row.get("rate")),
                List.copyOf(sourceTags), text(row.get("comment")), text(row.get("date")), text(row.get("image")), text(row.get("sourceUpdatedAt")));
        try { validate(data); } catch (IllegalArgumentException e) { throw new ExternalApiException("上游收藏字段异常，未生成预览：" + e.getMessage()); }
        if (json(data).getBytes(StandardCharsets.UTF_8).length > 64000) throw new ExternalApiException("收藏原始内容超过64KB保护上限，未生成预览");
        return data;
    }
    public static void validate(CollectionData data) {
        if (data == null || data.subjectId() == null || data.subjectId() <= 0 || data.tags() == null || data.tags().size() > 100
                || data.tags().stream().anyMatch(t -> t == null || t.length() > 200)
                || (data.rate() != null && (data.rate() < 0 || data.rate() > 10))) throw new IllegalArgumentException("外部收藏字段无效");
        for (var field : Arrays.asList(data.name(), data.nameCn())) bounded(field, 1000);
        bounded(data.comment(), 16000); bounded(data.image(), 2048); bounded(data.date(), 32); bounded(data.sourceUpdatedAt(), 100);
    }
    private static void bounded(String text, int max) { if (text == null || text.length() > max) throw new IllegalArgumentException("外部收藏文字超出限制"); }
    public static String account(String value) {
        if (value != null) value = value.strip();
        if (value == null || value.isBlank() || value.length() > 100
                || java.util.regex.Pattern.compile("(?U)[\\s\\p{Cntrl}/?#\\\\]").matcher(value).find())
            throw new IllegalArgumentException("请输入个人主页/user/后的账号标识，不能填写完整链接");
        return value.strip();
    }
    private static void owner(String value) {
        if (value == null || value.isBlank() || value.length() > 100) throw new IllegalArgumentException("本地登录标识无效");
    }
    public static void uuid(String value) {
        try { if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (Exception e) { throw new IllegalArgumentException("导入编号必须为标准UUID"); }
    }
    private static Integer number(Object value) {
        if (value == null) return null;
        if (value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() == n.intValue()) return n.intValue();
        throw new ExternalApiException("收藏数值字段异常，请重新读取");
    }
    private static String text(Object value) {
        if (value == null) return "";
        if (value instanceof String text) return text;
        throw new ExternalApiException("收藏文字字段异常");
    }
    private static String title(CollectionData data) { return (data.nameCn().isBlank() ? data.name() : data.nameCn()).strip(); }
    private static AnimeStatus state(Integer type) {
        if (type == null) return null;
        return switch (type) { case 1 -> AnimeStatus.PLANNING; case 2 -> AnimeStatus.FINISHED; case 3 -> AnimeStatus.WATCHING; case 5 -> AnimeStatus.DROPPED; default -> null; };
    }
    private static String season(String value) {
        try { LocalDate date = LocalDate.parse(value); return date.getYear() + new String[]{"冬", "春", "夏", "秋"}[(date.getMonthValue() - 1) / 3]; }
        catch (Exception e) { return ""; }
    }
    private static int count(List<Row> rows, String result) { return (int) rows.stream().filter(r -> r.result().equals(result)).count(); }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("收藏内容无法保存", e); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); } catch (Exception e) { throw new IllegalArgumentException("收藏存档格式无效", e); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private record Pending(String owner, Preview preview, String fingerprint) {}
}
