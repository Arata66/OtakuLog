package com.otakulog.service;

import com.fasterxml.jackson.databind.*;
import com.otakulog.dto.BackupDocument;
import com.otakulog.dto.BackupDocument.*;
import com.otakulog.dto.BangumiImportDTO.*;
import com.otakulog.entity.*;
import com.otakulog.repository.*;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ExternalCollectionBackup {
    private final ExternalCollectionRepository collections;
    private final ImportBatchRepository batches;
    private final ImportLockRepository locks;
    private final ObjectMapper mapper;
    public ExternalCollectionBackup(ExternalCollectionRepository collections, ImportBatchRepository batches,
            ImportLockRepository locks, ObjectMapper mapper) {
        this.collections = collections; this.batches = batches; this.locks = locks;
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
    public List<ExternalEntry> exportCollections() { return collections.findAll(Sort.by("id")).stream().map(this::entry).toList(); }
    public List<ImportBatchEntry> exportBatches() { return batches.findAll(Sort.by("id")).stream().map(this::entry).toList(); }
    public void lock() {
        // 空库恢复也会新增作品，必须与确认导入共用身份核对边界。
        locks.lockSingleton().orElseThrow(() -> new IllegalStateException("导入锁缺失，请完成数据库迁移"));
    }
    public void validate(BackupDocument document) {
        require(document.externalCollections() != null && document.importBatches() != null, "外部集合缺失");
        require(document.externalCollections().size() <= 100000 && document.importBatches().size() <= 100000, "外部集合超过保护上限");
        var keys = new HashSet<String>(); var identities = new HashSet<String>();
        for (var e : document.externalCollections()) {
            BangumiImportPreviewService.uuid(e.key()); require(keys.add(e.key()), "外部收藏编号重复"); account(e.username());
            require(e.subjectId() != null && e.subjectId() > 0 && identities.add(e.username() + ":" + e.subjectId()), "外部收藏来源身份无效或重复");
            require(e.version() != null && e.version() >= 0, "外部收藏版本无效");
            audit(e.createdAt(), e.updatedAt()); audit(e.createdAt(), e.capturedAt());
            require(!e.capturedAt().isAfter(e.updatedAt()), "外部抓取时间晚于修改时间");
            var data = payload(e.payload(), CollectionData.class); BangumiImportPreviewService.validate(data);
            require(e.subjectId().equals(data.subjectId()), "外部收藏编号与原文不同");
        }
        keys.clear();
        for (var e : document.importBatches()) {
            BangumiImportPreviewService.uuid(e.requestKey()); require(keys.add(e.requestKey()), "导入请求编号重复");
            require(e.owner() != null && !e.owner().isBlank() && e.owner().length() <= 100, "导入归属无效"); account(e.username());
            require(e.fingerprint() != null && e.fingerprint().matches("[0-9a-f]{64}"), "导入指纹无效"); audit(e.createdAt(), e.updatedAt());
            var result = payload(e.resultJson(), Result.class);
            require(e.requestKey().equals(result.requestKey()) && result.created() >= 0 && result.skipped() >= 0
                    && result.needsReview() >= 0 && result.needsReview() <= result.skipped() && result.total() >= 0
                    && result.total() <= 10000 && result.created() + (long) result.skipped() == result.total()
                    && result.externalSaved() == result.total(), "导入结果计数或编号无效");
        }
    }
    public void analyze(BackupDocument document, List<String> conflicts, Map<String, Object> summary) {
        int added = 0, addedBatches = 0;
        for (var e : document.externalCollections()) {
            var keyed = collections.findByCollectionKey(e.key()).orElse(null);
            var identity = collections.findByUsernameAndSubjectId(e.username(), e.subjectId()).orElse(null);
            if (keyed == null && identity == null) { added++; continue; }
            if (keyed == null || identity == null || !Objects.equals(keyed.getId(), identity.getId()) || !entry(keyed).equals(e))
                conflicts.add("外部收藏 " + e.key() + "：来源身份或内容与本地不同");
        }
        for (var e : document.importBatches()) {
            var local = batches.findByRequestKey(e.requestKey()).orElse(null);
            if (local == null) addedBatches++;
            else if (!entry(local).equals(e)) conflicts.add("导入批次 " + e.requestKey() + "：归属或结果与本地不同");
        }
        summary.put("externalCollections", document.externalCollections().size()); summary.put("newExternalCollections", added);
        summary.put("importBatches", document.importBatches().size()); summary.put("newImportBatches", addedBatches);
    }
    public void restore(BackupDocument document) {
        for (var e : document.externalCollections()) {
            if (collections.findByCollectionKey(e.key()).isPresent()) continue;
            var value = new ExternalCollection(); value.setCollectionKey(e.key()); value.setUsername(e.username());
            value.setSubjectId(e.subjectId()); value.setPayload(e.payload()); value.setVersion(e.version());
            value.setCollectionType(payload(e.payload(), CollectionData.class).type()); value.setCapturedAt(e.capturedAt());
            value.setCreatedAt(e.createdAt()); value.setUpdatedAt(e.updatedAt()); collections.save(value);
        }
        for (var e : document.importBatches()) {
            if (batches.findByRequestKey(e.requestKey()).isPresent()) continue;
            var value = new ImportBatch(); value.setRequestKey(e.requestKey()); value.setOwner(e.owner());
            value.setUsername(e.username()); value.setFingerprint(e.fingerprint()); value.setResultJson(e.resultJson());
            value.setCreatedAt(e.createdAt()); value.setUpdatedAt(e.updatedAt()); batches.save(value);
        }
    }
    private ExternalEntry entry(ExternalCollection e) {
        return new ExternalEntry(e.getCollectionKey(), e.getUsername(), e.getSubjectId(), e.getPayload(), e.getVersion(),
                e.getCapturedAt(), e.getCreatedAt(), e.getUpdatedAt());
    }
    private ImportBatchEntry entry(ImportBatch e) {
        return new ImportBatchEntry(e.getRequestKey(), e.getOwner(), e.getUsername(), e.getFingerprint(), e.getResultJson(), e.getCreatedAt(), e.getUpdatedAt());
    }
    private <T> T payload(String json, Class<T> type) {
        require(json != null && json.getBytes(StandardCharsets.UTF_8).length <= 64000, "外部原文缺失或过长");
        try {
            var tree = mapper.readTree(json);
            require(tree != null && tree.isObject(), "外部原文须为完整对象");
            for (var field : type.getRecordComponents()) {
                require(tree.has(field.getName()), "外部原文字段缺失");
                var value = tree.get(field.getName());
                // Jackson仍会把数字转换为String，必须先核对原始JSON类型。
                if (field.getType() == String.class) require(value.isTextual(), "外部文字字段类型无效");
                else if (field.getType() == List.class) {
                    require(value.isArray(), "外部标签须为文字数组");
                    for (var tag : value) require(tag.isTextual(), "外部标签元素须为文字");
                } else require((field.getType() == Integer.class && value.isNull())
                        || (value.isIntegralNumber() && value.canConvertToInt()), "外部整数字段类型无效");
            }
            return mapper.treeToValue(tree, type);
        } catch (Exception e) { throw new IllegalArgumentException("外部存档格式无效", e); }
    }
    private void account(String value) {
        require(value != null && value.equals(BangumiImportPreviewService.account(value).toLowerCase(Locale.ROOT)), "外部账号不规范");
    }
    private void audit(LocalDateTime created, LocalDateTime updated) {
        require(created != null && updated != null && created.getYear() >= 1000 && !updated.isBefore(created), "外部审计时间无效");
        require(created.getNano() % 1000 == 0 && updated.getNano() % 1000 == 0, "外部时间必须为微秒精度");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
