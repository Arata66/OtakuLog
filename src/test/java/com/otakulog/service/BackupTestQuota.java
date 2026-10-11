package com.otakulog.service;

import com.otakulog.entity.AiQuota;
import com.otakulog.repository.AiQuotaRepository;
import java.time.LocalDate;

public final class BackupTestQuota {
    private BackupTestQuota() {}
    public static void seed(AiQuotaRepository repository) {
        // H2测试禁用迁移，显式准备生产V12提供的固定额度行。
        repository.deleteAll();
        var quota = new AiQuota(); quota.setId(1L); quota.setQuotaDate(LocalDate.now()); quota.setReservedCalls(0);
        repository.saveAndFlush(quota);
    }
}
