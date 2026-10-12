package com.otakulog.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.otakulog.dto.BackupDocument;
import com.otakulog.dto.BackupDocument.*;
import com.otakulog.entity.*;
import com.otakulog.enums.*;
import com.otakulog.repository.*;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BackupService {
    private final AnimeRepository anime;
    private final TagRepository tags;
    private final AnimeGroupRepository groups;
    private final EpisodeRecordRepository episodes;
    private final AnimeMemoryRepository memories;
    private final MemoryInsightRepository insights;
    private final AiCallRepository aiCalls;
    private final AiQuotaRepository quota;
    private final ExternalCollectionBackup externalBackup;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public BackupService(AnimeRepository anime, TagRepository tags, AnimeGroupRepository groups,
                         EpisodeRecordRepository episodes, AnimeMemoryRepository memories,
                         MemoryInsightRepository insights, AiCallRepository aiCalls, AiQuotaRepository quota, ExternalCollectionBackup externalBackup) {
        this.anime = anime; this.tags = tags; this.groups = groups; this.episodes = episodes; this.memories = memories;
        this.insights = insights; this.aiCalls = aiCalls; this.quota = quota;
        this.externalBackup = externalBackup;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String exportJson() {
        var allAnime = anime.findAll(Sort.by("id"));
        var allGroups = groups.findAll(Sort.by("id"));
        var links = new ArrayList<Membership>();
        for (var group : allGroups) {
            groups.findAnimeIdsByGroupId(group.getId()).stream().sorted()
                    .forEach(id -> links.add(new Membership("g:" + group.getId(), "a:" + id)));
        }
        var allMemories = memories.findAll(Sort.by("id"));
        var memoryKeys = allMemories.stream().collect(Collectors.toMap(AnimeMemory::getId, AnimeMemory::getMemoryKey));
        var document = new BackupDocument("otakulog-backup", 4, LocalDateTime.now(),
                allAnime.stream().map(a -> new AnimeEntry("a:" + a.getId(), data(a),
                        a.getTags().stream().sorted(Comparator.comparing(Tag::getId))
                                .map(t -> "t:" + t.getId()).toList())).toList(),
                tags.findAll(Sort.by("id")).stream().map(t -> new TagEntry("t:" + t.getId(), t.getName(), t.getCreatedAt())).toList(),
                allGroups.stream().map(g -> new GroupEntry("g:" + g.getId(), g.getName(), g.getDescription(),
                        g.getColor(), g.getSortOrder(), g.getCreatedAt(), g.getUpdatedAt())).toList(), links,
                episodes.findAll(Sort.by("animeId", "episodeNumber")).stream().map(e -> new EpisodeEntry(
                        "a:" + e.getAnimeId(), e.getEpisodeNumber(), e.getWatchedDate(), e.getSource(),
                        e.getCreatedAt(), e.getUpdatedAt())).toList(),
                allMemories.stream().map(m -> new MemoryEntry(
                        m.getMemoryKey(), "a:" + m.getAnimeId(), m.getContent(), m.getLiked(), m.getDisliked(),
                        m.getScope(), m.getContext(), m.getWatchedDate(), m.getVersion(),
                        m.getCreatedAt(), m.getUpdatedAt())).toList(),
                insights.findAll(Sort.by("id")).stream().map(i -> insightEntry(i, memoryKeys.get(i.getMemoryId()))).toList(),
                aiCalls.findAll(Sort.by("id")).stream().map(BackupService::callEntry).toList(),
                externalBackup.exportCollections(), externalBackup.exportBatches());
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document);
        } catch (Exception e) { throw new IllegalStateException("备份导出失败", e); }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> previewJson(String json) {
        try {
            Input input = parse(json);
            return analyze(input, false).summary;
        } catch (IllegalArgumentException e) {
            return Map.of("valid", false, "conflicts", List.of(e.getMessage()), "warnings", List.of());
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> importJson(String json) {
        Input input = parse(json);
        // 与付费预留共用锁顺序，旧格式恢复也不能绕过额度保护。
        AiQuota lockedQuota = quota.findSingletonForUpdate().orElseThrow(() -> new IllegalStateException("AI额度行缺失，请先完成数据库迁移"));
        externalBackup.lock();
        LocalDate today = LocalDate.now();
        int retainedCalls = today.equals(lockedQuota.getQuotaDate()) ? lockedQuota.getReservedCalls() : 0;
        Analysis analysis = analyze(input, true);
        if (!analysis.conflicts.isEmpty()) throw new IllegalArgumentException(String.join("；", analysis.conflicts));
        BackupDocument doc = input.document;
        Map<String, Tag> tagMap = new HashMap<>();
        for (var t : doc.tags()) {
            Tag target = tags.findByName(t.name()).orElseGet(() -> {
                Tag created = new Tag(t.name()); created.setCreatedAt(t.createdAt());
                return tags.save(created);
            });
            tagMap.put(t.key(), target);
        }
        Map<String, Anime> animeMap = new HashMap<>();
        for (var entry : doc.anime()) {
            Anime target = analysis.matches.get(entry.key());
            if (target == null) {
                target = new Anime(); applyData(target, entry.data());
                target.setTags(entry.tagKeys().stream().map(tagMap::get).collect(Collectors.toSet()));
            } else {
                // 合并恢复只补充本地缺失信息，已有资料和更高进度属于本地。
                var d = entry.data();
                if (target.getBangumiId() == null) target.setBangumiId(d.bangumiId());
                if (value(d.currentEpisode()) > value(target.getCurrentEpisode())) {
                    if (d.totalEpisodes() != null && (target.getTotalEpisodes() == null || d.totalEpisodes() > target.getTotalEpisodes()))
                        target.setTotalEpisodes(d.totalEpisodes());
                    target.setCurrentEpisode(d.currentEpisode()); target.setStatus(d.status()); target.setEndDate(d.endDate());
                    if (target.getWatchStartDate() == null) target.setWatchStartDate(d.watchStartDate());
                }
                for (String key : entry.tagKeys()) target.getTags().add(tagMap.get(key));
            }
            animeMap.put(entry.key(), anime.save(target));
        }
        for (var g : doc.groups()) {
            AnimeGroup target = analysis.groupMatches.get(g.key());
            if (target == null) {
                target = new AnimeGroup(); target.setName(g.name()); target.setDescription(g.description());
                target.setColor(g.color()); target.setSortOrder(g.sortOrder());
                target.setCreatedAt(g.createdAt()); target.setUpdatedAt(g.updatedAt()); groups.save(target);
            }
            Set<Long> members = new HashSet<>(groups.findAnimeIdsByGroupId(target.getId()));
            for (var link : doc.memberships()) {
                if (link.groupKey().equals(g.key()) && members.add(animeMap.get(link.animeKey()).getId()))
                    groups.addAnimeToGroup(target.getId(), animeMap.get(link.animeKey()).getId());
            }
        }
        for (var e : doc.episodes()) {
            Long id = animeMap.get(e.animeKey()).getId();
            EpisodeRecord target = episodes.findByAnimeIdAndEpisodeNumber(id, e.episodeNumber()).orElse(null);
            if (target == null) {
                target = new EpisodeRecord(); target.setAnimeId(id); target.setEpisodeNumber(e.episodeNumber());
                target.setWatchedDate(e.watchedDate()); target.setSource(e.source());
                target.setCreatedAt(e.createdAt()); target.setUpdatedAt(e.updatedAt()); episodes.save(target);
            } else if (target.getWatchedDate() == null && e.watchedDate() != null) {
                target.setWatchedDate(e.watchedDate()); target.setSource(e.source()); episodes.save(target);
            }
        }
        Map<String, AnimeMemory> memoryMap = new HashMap<>();
        for (var m : doc.memories()) {
            var existing = memories.findByMemoryKey(m.key()).orElse(null);
            if (existing != null) { memoryMap.put(m.key(), existing); continue; }
            AnimeMemory target = new AnimeMemory();
            target.setMemoryKey(m.key()); target.setAnimeId(animeMap.get(m.animeKey()).getId());
            target.setContent(m.content()); target.setLiked(m.liked()); target.setDisliked(m.disliked());
            target.setScope(m.scope()); target.setContext(m.context()); target.setWatchedDate(m.watchedDate());
            target.setVersion(m.version()); target.setCreatedAt(m.createdAt()); target.setUpdatedAt(m.updatedAt());
            memoryMap.put(m.key(), memories.save(target));
        }
        for (var i : doc.insights()) {
            if (insights.findByInsightKey(i.key()).isPresent()) continue;
            insights.save(restoreInsight(i, memoryMap.get(i.memoryKey()).getId()));
        }
        for (var c : doc.aiCalls()) {
            if (aiCalls.findByRequestKey(c.requestKey()).isPresent()) continue;
            aiCalls.save(restoreCall(c));
        }
        long mergedCount = aiCalls.countCreatedInDay(today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        require(mergedCount <= Integer.MAX_VALUE, "当日调用记录超出额度计数范围");
        lockedQuota.setQuotaDate(today);
        lockedQuota.setReservedCalls(Math.max(retainedCalls, (int) mergedCount));
        quota.save(lockedQuota);
        externalBackup.restore(doc);
        return analysis.summary;
    }

    private Analysis analyze(Input input, boolean lock) {
        var doc = input.document;
        // 恢复取得作品锁后才载入实体，避免一级缓存把锁前进度和资料带回写入。
        List<Anime> localAnime = lock
                ? anime.findAllIdsForRestore().stream().map(id -> anime.findByIdForUpdate(id).orElseThrow()).toList()
                : anime.findAll(Sort.by("id"));
        List<String> conflicts = new ArrayList<>();
        Map<String, Anime> matches = new HashMap<>();
        for (var entry : doc.anime()) {
            var d = entry.data();
            var candidates = localAnime.stream().filter(a -> a.getName().equals(d.name())
                    || (d.bangumiId() != null && d.bangumiId().equals(a.getBangumiId()))).toList();
            if (candidates.size() > 1) { conflicts.add(d.name() + "：本地身份匹配不唯一"); continue; }
            if (candidates.isEmpty()) continue;
            Anime target = candidates.get(0);
            if (!target.getName().equals(d.name()) || (target.getBangumiId() != null && d.bangumiId() != null
                    && !target.getBangumiId().equals(d.bangumiId()))) {
                conflicts.add(d.name() + "：名称和 Bangumi ID 冲突"); continue;
            }
            matches.put(entry.key(), target);
            if (input.legacy && value(d.currentEpisode()) < value(target.getCurrentEpisode()))
                conflicts.add(d.name() + "：导入进度不能低于已有进度");
        }
        Map<String, AnimeGroup> groupMatches = new HashMap<>();
        var localGroups = groups.findAll();
        for (var entry : doc.groups()) {
            var candidates = localGroups.stream().filter(g -> g.getName().equals(entry.name())).toList();
            if (candidates.size() > 1) { conflicts.add(entry.name() + "：本地分组名称不唯一"); continue; }
            if (candidates.isEmpty()) continue;
            var g = candidates.get(0); groupMatches.put(entry.key(), g);
            if (!Objects.equals(g.getDescription(), entry.description()) || !Objects.equals(g.getColor(), entry.color())
                    || !Objects.equals(g.getSortOrder(), entry.sortOrder())) conflicts.add(entry.name() + "：同名分组资料不同");
        }
        int newEpisodes = 0, filledDates = 0;
        for (var e : doc.episodes()) {
            Anime target = matches.get(e.animeKey());
            EpisodeRecord local = target == null ? null : episodes.findByAnimeIdAndEpisodeNumber(target.getId(), e.episodeNumber()).orElse(null);
            if (local == null) { newEpisodes++; continue; }
            if (local.getWatchedDate() != null && e.watchedDate() != null
                    && (!local.getWatchedDate().equals(e.watchedDate()) || local.getSource() != e.source()))
                conflicts.add(target.getName() + " 第 " + e.episodeNumber() + " 集：观看日期或来源冲突");
            if (local.getWatchedDate() == null && e.watchedDate() != null) filledDates++;
        }
        int newMemories = 0;
        for (var m : doc.memories()) {
            AnimeMemory local = memories.findByMemoryKey(m.key()).orElse(null);
            if (local == null) { newMemories++; continue; }
            Anime target = matches.get(m.animeKey());
            // 稳定编号只用于识别同一条记忆，不能将旧感想覆盖到另一作品或新版本。
            if (target == null || !Objects.equals(local.getAnimeId(), target.getId()) || !sameMemory(local, m))
                conflicts.add("观影记忆 " + m.key() + "：所属作品或内容与本地不同，请保留两份后核对");
        }
        int newInsights = 0, newCalls = 0;
        Map<String, Integer> addedInsights = new HashMap<>();
        for (var i : doc.insights()) {
            var local = insights.findByInsightKey(i.key()).orElse(null);
            if (local == null) { newInsights++; addedInsights.merge(i.memoryKey(), 1, Integer::sum); continue; }
            var owner = memories.findById(local.getMemoryId()).orElse(null);
            if (owner == null || !insightEntry(local, owner.getMemoryKey()).equals(i))
                conflicts.add("观点 " + i.key() + "：所属记忆或内容与本地不同");
        }
        for (var entry : addedInsights.entrySet()) {
            var owner = memories.findByMemoryKey(entry.getKey()).orElse(null);
            long existing = owner == null ? 0 : insights.countByMemoryId(owner.getId());
            if (existing + entry.getValue() > 200) conflicts.add("记忆 " + entry.getKey() + "：合并后观点超过200条");
        }
        for (var c : doc.aiCalls()) {
            var local = aiCalls.findByRequestKey(c.requestKey()).orElse(null);
            if (local == null) { newCalls++; continue; }
            if (!callEntry(local).equals(c)) conflicts.add("调用 " + c.requestKey() + "：归属或历史与本地不同");
        }
        Set<String> localTags = tags.findAll().stream().map(Tag::getName).collect(Collectors.toSet());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", conflicts.isEmpty()); result.put("format", "otakulog-backup"); result.put("version", doc.version()); result.put("legacy", input.legacy);
        result.put("created", doc.anime().size() - matches.size()); result.put("updated", matches.size());
        result.put("tags", doc.tags().size()); result.put("newTags", (int) doc.tags().stream().filter(t -> !localTags.contains(t.name())).count());
        result.put("groups", doc.groups().size()); result.put("newGroups", doc.groups().size() - groupMatches.size());
        result.put("memberships", doc.memberships().size()); result.put("episodes", doc.episodes().size());
        result.put("newEpisodes", newEpisodes); result.put("filledDates", filledDates);
        result.put("memories", doc.memories().size()); result.put("newMemories", newMemories);
        result.put("insights", doc.insights().size()); result.put("newInsights", newInsights);
        result.put("aiCalls", doc.aiCalls().size()); result.put("newAiCalls", newCalls);
        result.put("warnings", input.legacy ? List.of("旧格式没有逐集日期和分组，缺失记录仅补未知日期。", "已有资料和更高进度会保留，标签与分组关联取并集。")
                : List.of("已有资料和更高进度会保留，标签与分组关联取并集；本地额外数据不会删除。"));
        result.put("conflicts", conflicts);
        externalBackup.analyze(doc, conflicts, result);
        result.put("valid", conflicts.isEmpty());
        return new Analysis(matches, groupMatches, conflicts, result);
    }

    private Input parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null) throw new IllegalArgumentException("备份不能为空");
            boolean legacy = root.isArray();
            BackupDocument document;
            if (legacy) document = legacyDocument(root);
            else {
                require(root.isObject() && "otakulog-backup".equals(root.path("format").asText()), "备份格式不受支持");
                require(root.path("version").isIntegralNumber()
                        && root.path("version").longValue() >= 1 && root.path("version").longValue() <= 4, "备份版本不受支持");
                if (root.path("version").intValue() < 4) {
                    require(!root.has("externalCollections") && !root.has("importBatches"), "旧版本不能包含外部收藏或导入批次，请使用版本四");
                    ((ObjectNode) root).putArray("externalCollections"); ((ObjectNode) root).putArray("importBatches");
                }
                if (root.path("version").intValue() < 3) {
                    require(!root.has("insights") && !root.has("aiCalls"), "旧版本不能包含观点或调用历史，请使用版本三");
                    ((ObjectNode) root).putArray("insights"); ((ObjectNode) root).putArray("aiCalls");
                }
                if (root.path("version").intValue() == 1) {
                    require(!root.has("memories"), "版本一不能包含观影记忆，请使用版本二");
                    ((ObjectNode) root).putArray("memories");
                }
                fields(root, BackupDocument.class);
                require(root.path("externalCollections").isArray() && root.path("importBatches").isArray(), "外部集合必须为数组");
                for (JsonNode entry : root.path("externalCollections")) { fields(entry, ExternalEntry.class); auditFields(entry); }
                for (JsonNode entry : root.path("importBatches")) { fields(entry, ImportBatchEntry.class); auditFields(entry); }
                require(root.path("insights").isArray() && root.path("aiCalls").isArray(), "观点与调用集合必须为数组");
                for (JsonNode entry : root.path("insights")) { fields(entry, InsightEntry.class); auditFields(entry); }
                for (JsonNode entry : root.path("aiCalls")) {
                    fields(entry, AiCallEntry.class); auditFields(entry);
                    require(entry.path("completedAt").isNull() || entry.path("completedAt").isTextual(), "调用完成时间必须为ISO文字或null");
                }
                require(root.path("memories").isArray(), "观影记忆集合必须为数组");
                for (JsonNode entry : root.path("memories")) {
                    fields(entry, MemoryEntry.class);
                    require(entry.path("context").isTextual(), "观影记忆语境必须为文字");
                    require(entry.path("watchedDate").isNull() || entry.path("watchedDate").isTextual(), "观影记忆观看日期必须为日期文字或null");
                    require(entry.path("createdAt").isTextual() && entry.path("updatedAt").isTextual(), "观影记忆审计时间必须为ISO文字");
                }
                for (JsonNode entry : root.path("anime")) { fields(entry, AnimeEntry.class); fields(entry.path("data"), AnimeData.class); }
                for (JsonNode entry : root.path("tags")) {
                    fields(entry, TagEntry.class); require(!entry.path("createdAt").isNull(), "标签创建时间不能为空");
                }
                for (JsonNode entry : root.path("groups")) fields(entry, GroupEntry.class);
                for (JsonNode entry : root.path("memberships")) fields(entry, Membership.class);
                for (JsonNode entry : root.path("episodes")) fields(entry, EpisodeEntry.class);
                document = mapper.treeToValue(root, BackupDocument.class);
            }
            validate(document);
            externalBackup.validate(document);
            return new Input(document, legacy);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("备份结构、字段或日期格式无效", e); }
    }

    private void validate(BackupDocument d) {
        require(d.exportedAt() != null, "缺少导出时间");
        var a = index(d.anime(), AnimeEntry::key); var t = index(d.tags(), TagEntry::key); var g = index(d.groups(), GroupEntry::key);
        require(d.episodes() != null && d.memberships() != null, "缺少记录或关联集合");
        index(d.memories(), MemoryEntry::key);
        for (var m : d.memories()) {
            require(a.containsKey(m.animeKey()), "观影记忆的作品不存在");
            try { require(UUID.fromString(m.key()).toString().equals(m.key()), "观影记忆编号必须为规范UUID"); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("观影记忆编号必须为规范UUID"); }
            text(m.content(), 16000, true); text(m.liked(), 2000, false); text(m.disliked(), 2000, false); text(m.scope(), 200, false);
            require(m.context() != null && m.version() != null && m.version() >= 0, "观影记忆语境或版本无效");
            require(m.watchedDate() == null || (m.watchedDate().getYear() >= 1000 && !m.watchedDate().isAfter(LocalDate.now())), "观影记忆观看日期无效");
            require(m.createdAt() != null && m.updatedAt() != null && m.createdAt().getYear() >= 1000
                    && m.createdAt().getYear() <= 9999 && m.updatedAt().getYear() <= 9999
                    && !m.updatedAt().isBefore(m.createdAt()), "观影记忆记录或修改时间无效");
            // 数据库保存到微秒，拒绝更细时间，避免再次恢复原文件时被舍入差异误判冲突。
            require(m.createdAt().getNano() % 1000 == 0 && m.updatedAt().getNano() % 1000 == 0, "观影记忆审计时间最多支持微秒精度");
        }
        validateTaste(d);
        Set<String> names = new HashSet<>(); Set<Integer> ids = new HashSet<>();
        for (var entry : d.anime()) {
            var data = entry.data(); require(data != null, "缺少作品资料");
            require(data.legacy() != null, "缺少历史作品标记");
            text(data.name(), 255, true); require(names.add(data.name()), "备份作品名称重复");
            require(data.bangumiId() == null || (data.bangumiId() > 0 && ids.add(data.bangumiId())), "Bangumi ID 无效或重复");
            require(data.currentEpisode() == null || data.currentEpisode() >= 0, "观看进度无效");
            require(data.totalEpisodes() == null || (data.totalEpisodes() > 0 && value(data.currentEpisode()) <= data.totalEpisodes()), "总集数或观看进度无效");
            require(data.score() == null || (Double.isFinite(data.score()) && data.score() >= 0 && data.score() <= 10), "评分必须在 0 到 10 之间");
            require(data.broadcastDay() == null || (data.broadcastDay() >= 1 && data.broadcastDay() <= 7), "放送星期无效");
            text(data.season(), 255, false); text(data.remark(), 255, false); text(data.coverUrl(), 255, false); text(data.watchSeason(), 20, false);
            require(entry.tagKeys() != null && new HashSet<>(entry.tagKeys()).size() == entry.tagKeys().size(), "标签关联缺失或重复");
            for (String key : entry.tagKeys()) require(t.containsKey(key), "标签关联不存在");
        }
        names.clear();
        for (var entry : d.tags()) { text(entry.name(), 50, true); require(names.add(entry.name()), "标签名称重复"); }
        names.clear();
        for (var entry : d.groups()) {
            text(entry.name(), 100, true); text(entry.description(), 500, false); text(entry.color(), 20, false);
            require(names.add(entry.name()), "分组名称重复");
        }
        Set<Membership> links = new HashSet<>();
        for (var link : d.memberships()) require(link != null && a.containsKey(link.animeKey()) && g.containsKey(link.groupKey()) && links.add(link), "分组关联无效或重复");
        Set<String> recordKeys = new HashSet<>();
        for (var e : d.episodes()) {
            require(e != null && a.containsKey(e.animeKey()), "逐集记录的作品不存在");
            require(e.episodeNumber() != null && e.episodeNumber() > 0 && e.source() != null, "逐集编号或来源无效");
            require(recordKeys.add(e.animeKey() + ":" + e.episodeNumber()), "逐集记录重复");
            require(e.source() != EpisodeRecordSource.WATCHED || e.watchedDate() != null, "实际观看记录缺少日期");
        }
    }

    private BackupDocument legacyDocument(JsonNode root) throws Exception {
        Map<String, AnimeEntry> entries = new LinkedHashMap<>(); Map<String, TagEntry> tagEntries = new LinkedHashMap<>();
        for (JsonNode row : root) {
            require(row.isObject(), "旧格式作品必须为对象");
            ObjectNode data = ((ObjectNode) row).deepCopy();
            data.remove(List.of("id", "tags", "progress", "statusDisplay"));
            String status = row.path("status").asText("WATCHING").toUpperCase(Locale.ROOT);
            data.put("status", status);
            if (!data.hasNonNull("currentEpisode")) data.put("currentEpisode", 0);
            if (!data.hasNonNull("legacy")) data.put("legacy", false);
            for (String field : List.of("startDate", "endDate", "watchStartDate"))
                if (data.has(field) && data.get(field).isTextual() && data.get(field).asText().isBlank()) data.putNull(field);
            if ("FINISHED".equals(status)) data.set("currentEpisode", data.get("totalEpisodes"));
            AnimeData parsed = mapper.treeToValue(data, AnimeData.class);
            require(parsed.totalEpisodes() != null && parsed.totalEpisodes() > 0 && parsed.status() != null, "旧格式缺少有效总集数或状态");
            require(parsed.status() != AnimeStatus.PLANNING || value(parsed.currentEpisode()) == 0, "计划状态不能包含观看进度");
            List<String> tagKeys = new ArrayList<>(); JsonNode rawTags = row.get("tags");
            List<String> tagNames = new ArrayList<>();
            if (rawTags != null && rawTags.isTextual()) tagNames.addAll(Arrays.asList(rawTags.asText().split(",")));
            else if (rawTags != null && rawTags.isArray()) for (JsonNode tag : rawTags) {
                require(tag.isTextual() || (tag.isObject() && tag.path("name").isTextual()), "旧标签格式无效");
                tagNames.add(tag.isTextual() ? tag.asText() : tag.get("name").asText());
            } else require(rawTags == null || rawTags.isNull(), "旧标签格式无效");
            for (String rawName : tagNames) {
                String name = rawName.trim(); if (name.isEmpty()) continue;
                var tag = tagEntries.computeIfAbsent(name, n -> new TagEntry("t:" + tagEntries.size(), n, null));
                if (!tagKeys.contains(tag.key())) tagKeys.add(tag.key());
            }
            var previous = entries.get(parsed.name());
            if (previous != null) require(previous.data().equals(parsed) && previous.tagKeys().equals(tagKeys), "旧格式同名作品资料不同");
            else entries.put(parsed.name(), new AnimeEntry("a:" + entries.size(), parsed, tagKeys));
        }
        var document = new BackupDocument("otakulog-backup", 1, LocalDateTime.now(), List.copyOf(entries.values()),
                List.copyOf(tagEntries.values()), List.of(), List.of(), List.of());
        // 先校验进度，再展开逐集记录，避免错误大数字在校验前耗尽内存。
        validate(document);
        long recordCount = entries.values().stream().mapToLong(e -> value(e.data().currentEpisode())).sum();
        require(recordCount <= 100_000, "旧格式一次最多补齐 100000 条记录，请拆分文件后导入");
        List<EpisodeEntry> records = new ArrayList<>();
        for (var entry : entries.values()) for (int n = 1; n <= value(entry.data().currentEpisode()); n++)
            records.add(new EpisodeEntry(entry.key(), n, null, EpisodeRecordSource.IMPORT, null, null));
        return new BackupDocument("otakulog-backup", 1, document.exportedAt(), List.copyOf(entries.values()),
                List.copyOf(tagEntries.values()), List.of(), List.of(), records);
    }

    private static InsightEntry insightEntry(MemoryInsight i, String memoryKey) {
        return new InsightEntry(i.getInsightKey(), memoryKey,
                i.getSourceVersion(), i.getQuoteField(), i.getQuote(), i.getStatement(), i.getDirection(),
                i.getAspect(), i.getFactor(), i.getStatus(), i.getOrigin(), i.getAiCallKey(),
                i.getSuggestedQuoteField(), i.getSuggestedQuote(), i.getSuggestedStatement(),
                i.getSuggestedDirection(), i.getSuggestedAspect(), i.getSuggestedFactor(),
                i.getVersion(), i.getCreatedAt(), i.getUpdatedAt());
    }
    private static AiCallEntry callEntry(AiCall c) {
        return new AiCallEntry(c.getRequestKey(), c.getMemoryKey(), c.getSourceVersion(), c.getInputHash(),
                c.getModel(), c.getPromptVersion(), c.getSelectedCharacters(), c.getStatus(), c.getErrorCategory(),
                c.getPromptTokens(), c.getCompletionTokens(), c.getCachedTokens(), c.getReasoningTokens(),
                c.getCreatedAt(), c.getUpdatedAt(), c.getCompletedAt());
    }
    private static MemoryInsight restoreInsight(InsightEntry i, Long memoryId) {
        var target = new MemoryInsight(); target.setInsightKey(i.key()); target.setMemoryId(memoryId);
        target.setSourceVersion(i.sourceVersion());
        target.setQuoteField(i.quoteField());
        target.setQuote(i.quote());
        target.setStatement(i.statement());
        target.setDirection(i.direction());
        target.setAspect(i.aspect());
        target.setFactor(i.factor());
        target.setStatus(i.status());
        target.setOrigin(i.origin());
        target.setAiCallKey(i.aiCallKey());
        target.setSuggestedQuoteField(i.suggestedQuoteField());
        target.setSuggestedQuote(i.suggestedQuote());
        target.setSuggestedStatement(i.suggestedStatement());
        target.setSuggestedDirection(i.suggestedDirection());
        target.setSuggestedAspect(i.suggestedAspect());
        target.setSuggestedFactor(i.suggestedFactor());
        target.setVersion(i.version());
        target.setCreatedAt(i.createdAt());
        target.setUpdatedAt(i.updatedAt());
        return target;
    }
    private static AiCall restoreCall(AiCallEntry c) {
        var target = new AiCall();
        target.setRequestKey(c.requestKey());
        target.setMemoryKey(c.memoryKey());
        target.setSourceVersion(c.sourceVersion());
        target.setInputHash(c.inputHash());
        target.setModel(c.model());
        target.setPromptVersion(c.promptVersion());
        target.setSelectedCharacters(c.selectedCharacters());
        target.setStatus(c.status());
        target.setErrorCategory(c.errorCategory());
        target.setPromptTokens(c.promptTokens());
        target.setCompletionTokens(c.completionTokens());
        target.setCachedTokens(c.cachedTokens());
        target.setReasoningTokens(c.reasoningTokens());
        target.setCreatedAt(c.createdAt());
        target.setUpdatedAt(c.updatedAt());
        target.setCompletedAt(c.completedAt());
        target.setHistoryOnly(c.status() == AiCallStatus.RUNNING);
        return target;
    }

    private static boolean sameMemory(AnimeMemory local, MemoryEntry m) {
        return Objects.equals(local.getContent(), m.content()) && Objects.equals(local.getLiked(), m.liked())
                && Objects.equals(local.getDisliked(), m.disliked()) && Objects.equals(local.getScope(), m.scope())
                && local.getContext() == m.context() && Objects.equals(local.getWatchedDate(), m.watchedDate())
                && Objects.equals(local.getVersion(), m.version()) && Objects.equals(local.getCreatedAt(), m.createdAt())
                && Objects.equals(local.getUpdatedAt(), m.updatedAt());
    }

    private static AnimeData data(Anime a) {
        return new AnimeData(a.getName(), a.getCurrentEpisode(), a.getTotalEpisodes(), a.getStatus(), a.getScore(),
                a.getSeason(), a.getRemark(), a.getCoverUrl(), a.getStartDate(), a.getEndDate(), a.getSortOrder(),
                a.getBroadcastDay(), a.getBangumiId(), a.getWatchStartDate(), a.isLegacy(), a.getWatchSeason(), a.getCreatedAt(), a.getUpdatedAt());
    }

    private static void applyData(Anime a, AnimeData d) {
        a.setName(d.name()); a.setCurrentEpisode(d.currentEpisode()); a.setTotalEpisodes(d.totalEpisodes());
        a.setStatus(d.status()); a.setScore(d.score()); a.setSeason(d.season()); a.setRemark(d.remark()); a.setCoverUrl(d.coverUrl());
        a.setStartDate(d.startDate()); a.setEndDate(d.endDate()); a.setSortOrder(d.sortOrder()); a.setBroadcastDay(d.broadcastDay());
        a.setBangumiId(d.bangumiId()); a.setWatchStartDate(d.watchStartDate()); a.setLegacy(Boolean.TRUE.equals(d.legacy()));
        a.setWatchSeason(d.watchSeason()); a.setCreatedAt(d.createdAt()); a.setUpdatedAt(d.updatedAt());
    }

    private static <T> Map<String, T> index(List<T> list, Function<T, String> key) {
        require(list != null, "备份缺少数据集合"); Map<String, T> result = new HashMap<>();
        for (T entry : list) {
            require(entry != null, "备份包含空记录"); String k = key.apply(entry); text(k, 100, true);
            require(result.putIfAbsent(k, entry) == null, "备份 key 重复");
        }
        return result;
    }

    private static void fields(JsonNode node, Class<?> type) {
        require(node.isObject(), "备份记录必须为对象");
        for (var component : type.getRecordComponents()) require(node.has(component.getName()), "备份缺少字段 " + component.getName());
    }

    private static void auditFields(JsonNode node) {
        require(node.path("createdAt").isTextual() && node.path("updatedAt").isTextual(), "审计时间必须为ISO文字");
    }

    private static void audit(LocalDateTime created, LocalDateTime updated) {
        require(created != null && updated != null && created.getYear() >= 1000 && created.getYear() <= 9999
                && updated.getYear() <= 9999 && !updated.isBefore(created), "审计时间无效");
        require(created.getNano() % 1000 == 0 && updated.getNano() % 1000 == 0, "审计时间最多支持微秒精度");
    }

    private static void uuid(String key) {
        try { require(key != null && UUID.fromString(key).toString().equals(key), "编号必须为规范UUID"); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("编号必须为规范UUID"); }
    }

    private static void nonNegative(Number value, boolean required) {
        require((!required && value == null) || (value != null && value.longValue() >= 0), "版本或用量必须为非负整数");
    }

    private static void insightFields(InsightQuoteField field, String quote, String statement,
            InsightDirection direction, InsightAspect aspect, String factor) {
        require(field != null && direction != null && aspect != null, "观点字段或枚举缺失");
        text(quote, 800, true); text(statement, 500, true); text(factor, 100, true);
    }

    private static void validateTaste(BackupDocument d) {
        var memoryMap = index(d.memories(), MemoryEntry::key);
        index(d.insights(), InsightEntry::key);
        var callMap = index(d.aiCalls(), AiCallEntry::requestKey);
        for (var c : d.aiCalls()) {
            uuid(c.requestKey()); uuid(c.memoryKey()); nonNegative(c.sourceVersion(), true);
            require(c.inputHash() != null && c.inputHash().matches("[0-9a-f]{64}"), "调用输入哈希无效");
            text(c.model(), 80, true); text(c.promptVersion(), 40, true); nonNegative(c.selectedCharacters(), true);
            require(c.selectedCharacters() >= 1 && c.selectedCharacters() <= 6000, "调用选文字数必须在1至6000之间");
            require(c.status() != null, "调用状态缺失");
            nonNegative(c.promptTokens(), false); nonNegative(c.completionTokens(), false);
            nonNegative(c.cachedTokens(), false); nonNegative(c.reasoningTokens(), false);
            audit(c.createdAt(), c.updatedAt());
            if (c.completedAt() != null) {
                audit(c.createdAt(), c.completedAt());
                require(!c.completedAt().isAfter(c.updatedAt()), "调用完成时间不能晚于修改时间");
            }
            require(c.status() == AiCallStatus.RUNNING ? c.completedAt() == null : c.completedAt() != null, "调用完成时间与状态不符");
        }
        Map<String, Integer> counts = new HashMap<>();
        for (var i : d.insights()) {
            uuid(i.key()); uuid(i.memoryKey()); nonNegative(i.sourceVersion(), true); nonNegative(i.version(), true);
            var memory = memoryMap.get(i.memoryKey());
            require(memory != null && i.sourceVersion() <= memory.version(), "观点来源缺失或版本超前");
            require(counts.merge(i.memoryKey(), 1, Integer::sum) <= 200, "单条记忆观点超过200条");
            insightFields(i.quoteField(), i.quote(), i.statement(), i.direction(), i.aspect(), i.factor());
            require(i.status() != null && i.origin() != null, "观点状态或来源缺失"); audit(i.createdAt(), i.updatedAt());
            if (i.sourceVersion().equals(memory.version())) {
                String source = switch (i.quoteField()) { case CONTENT -> memory.content(); case LIKED -> memory.liked(); case DISLIKED -> memory.disliked(); };
                require(source != null && source.contains(i.quote()), "当前观点引用不是来源原文");
            }
            if (i.origin() == InsightOrigin.MANUAL) {
                require(i.aiCallKey() == null && i.suggestedQuoteField() == null && i.suggestedQuote() == null
                        && i.suggestedStatement() == null && i.suggestedDirection() == null
                        && i.suggestedAspect() == null && i.suggestedFactor() == null, "手工观点不能夹带模型来源");
            } else {
                uuid(i.aiCallKey());
                insightFields(i.suggestedQuoteField(), i.suggestedQuote(), i.suggestedStatement(),
                        i.suggestedDirection(), i.suggestedAspect(), i.suggestedFactor());
                var call = callMap.get(i.aiCallKey());
                require(call != null && call.memoryKey().equals(i.memoryKey()) && call.sourceVersion() <= i.sourceVersion()
                        && call.status() == AiCallStatus.SUCCEEDED, "模型观点没有匹配的成功调用来源");
            }
        }
    }

    private static void text(String value, int max, boolean required) {
        require(!required || (value != null && !value.matches("(?U)[\\s\\p{javaWhitespace}]*")), "必填文字不能为空");
        require(value == null || value.length() <= max, "文字超出长度限制 " + max);
    }
    private static int value(Integer n) { return n == null ? 0 : n; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private record Input(BackupDocument document, boolean legacy) {}
    private record Analysis(Map<String, Anime> matches, Map<String, AnimeGroup> groupMatches,
                            List<String> conflicts, Map<String, Object> summary) {}
}
