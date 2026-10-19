package com.otakulog.config;

import com.otakulog.common.ConflictException;
import org.springframework.stereotype.Component;

@Component
public class TasteAiRuntime {
    private final TasteAiProperties properties;
    private Settings override;
    private int activeCalls;
    private boolean configuring;

    public TasteAiRuntime(TasteAiProperties properties) { this.properties = properties; }

    public synchronized Settings snapshot() {
        return override != null ? override : new Settings(properties.isEnabled(), properties.getApiKey(), properties.getDailyLimit());
    }

    public synchronized Lease acquireCall() {
        if (configuring) throw new ConflictException("AI 配置正在保存，请稍后重试");
        activeCalls++;
        return new Lease(this);
    }

    public synchronized Status lockSettings() {
        if (configuring || activeCalls > 0) throw busy();
        configuring = true;
        return status();
    }

    public synchronized Status cancelSettings() {
        configuring = false;
        return status();
    }

    public synchronized Status apply(boolean enabled, String apiKey, int dailyLimit) {
        if (activeCalls > 0) throw busy();
        if (apiKey == null || dailyLimit < 0 || dailyLimit > 1000 || apiKey.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("AI 配置格式无效");
        String key = apiKey.trim();
        if (key.length() > 512 || (enabled && key.isBlank())) throw new IllegalArgumentException("AI 配置格式无效");
        // 三字段一次替换，避免页面观察到密钥与预算来自不同次保存。
        override = new Settings(enabled, key, dailyLimit);
        configuring = false;
        return status();
    }

    private Status status() {
        var settings = snapshot();
        return new Status(settings.enabled(), settings.configured(), settings.dailyLimit());
    }

    private ConflictException busy() { return new ConflictException("AI 整理或配置正在进行，请稍后重试"); }

    private synchronized void release(Lease lease) {
        if (!lease.closed) {
            lease.closed = true;
            activeCalls--;
        }
    }

    public record Status(boolean enabled, boolean available, int dailyLimit) {}

    public static final class Settings {
        private final boolean enabled;
        private final String apiKey;
        private final int dailyLimit;
        private Settings(boolean enabled, String apiKey, int dailyLimit) {
            this.enabled = enabled;
            this.apiKey = apiKey;
            this.dailyLimit = dailyLimit;
        }
        public boolean enabled() { return enabled; }
        public String apiKey() { return apiKey; }
        public int dailyLimit() { return dailyLimit; }
        public boolean configured() { return enabled && apiKey != null && !apiKey.isBlank(); }
        @Override public String toString() { return "AI配置[enabled=" + enabled + ",dailyLimit=" + dailyLimit + "]"; }
    }

    public static final class Lease implements AutoCloseable {
        private final TasteAiRuntime owner;
        private boolean closed;
        private Lease(TasteAiRuntime owner) { this.owner = owner; }
        @Override public void close() { owner.release(this); }
    }
}
