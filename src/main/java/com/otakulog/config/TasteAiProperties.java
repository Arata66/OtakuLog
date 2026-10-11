package com.otakulog.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.net.URI;

@Component
@ConfigurationProperties(prefix = "app.ai")
public class TasteAiProperties {
    public static final String MODEL = "deepseek-flash";
    public static final String PROMPT_VERSION = "taste-v1";
    public static final int MAX_OUTPUT_TOKENS = 1200;
    private boolean enabled;
    private String apiKey = "";
    private String endpoint = "https://api.deepseek.com/chat/completions";
    private int dailyLimit = 20;
    private int connectTimeoutMs = 5000;
    private int responseTimeoutMs = 30000;
    private BigDecimal inputPricePerMillion = new BigDecimal("2");
    private BigDecimal outputPricePerMillion = new BigDecimal("8");
    @PostConstruct public void validate() {
        if (dailyLimit < 0 || dailyLimit > 1000 || connectTimeoutMs < 1 || responseTimeoutMs < 1
                || inputPricePerMillion == null || outputPricePerMillion == null
                || inputPricePerMillion.signum() < 0 || outputPricePerMillion.signum() < 0)
            throw new IllegalArgumentException("AI 配置范围非法");
        URI uri;
        try { uri = URI.create(endpoint); } catch (Exception e) { throw new IllegalArgumentException("AI 服务地址非法"); }
        boolean production = endpoint.equals("https://api.deepseek.com/chat/completions");
        boolean loopback = "http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost())
                || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost()) || "::1".equals(uri.getHost()));
        if ((!production && !loopback) || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("AI 服务只允许固定生产地址或本地回环假服务");
    }
    public boolean configured() { return enabled && apiKey != null && !apiKey.isBlank(); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String value) { endpoint = value; }
    public int getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(int value) { dailyLimit = value; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int value) { connectTimeoutMs = value; }
    public int getResponseTimeoutMs() { return responseTimeoutMs; }
    public void setResponseTimeoutMs(int value) { responseTimeoutMs = value; }
    public BigDecimal getInputPricePerMillion() { return inputPricePerMillion; }
    public void setInputPricePerMillion(BigDecimal value) { inputPricePerMillion = value; }
    public BigDecimal getOutputPricePerMillion() { return outputPricePerMillion; }
    public void setOutputPricePerMillion(BigDecimal value) { outputPricePerMillion = value; }
}
