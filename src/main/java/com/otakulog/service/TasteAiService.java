package com.otakulog.service;

import com.otakulog.dto.TasteAiDTO.*;
import com.otakulog.config.TasteAiRuntime;
import com.otakulog.enums.AiErrorCategory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class TasteAiService {
    private final TasteAiTransactions transactions;
    private final DeepSeekInsightClient client;
    private final TasteAiRuntime runtime;
    public TasteAiService(TasteAiTransactions transactions, DeepSeekInsightClient client, TasteAiRuntime runtime) {
        this.transactions = transactions; this.client = client; this.runtime = runtime;
    }
    public Preview preview(Long animeId, Long memoryId, Request request) { return transactions.preview(animeId, memoryId, request); }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result extract(Long animeId, Long memoryId, Request request) {
        // 从预留到最终读回都占用进程租约，保存配置无需等待供应商或持有数据库锁。
        try (var lease = runtime.acquireCall()) {
            Reservation prepared = transactions.reserve(animeId, memoryId, request);
            if (prepared.existing() != null) return prepared.existing();
            // 两段事务之间发送，供应商等待不会持有作品锁或额度锁。
            ProviderResult provider = client.extract(prepared.input());
            try { return transactions.finish(prepared, provider);
            } catch (RuntimeException e) {
                // 完成事务回滚后仍保留请求身份，不将异常链或供应商正文送回客户端。
                try { return transactions.finish(prepared, new ProviderResult(List.of(), provider.usage(), AiErrorCategory.INVALID_OUTPUT)); }
                catch (RuntimeException ignored) { return transactions.get(prepared.requestKey()); }
            }
        }
    }
    public Result get(String requestKey) { return transactions.get(requestKey); }
    public UsageSummary usage() { return transactions.usage(); }
}
