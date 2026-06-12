package com.otakulog.common;

// 外部 API 调用失败异常（Bangumi / trace.moe / WebDAV 等）
// GlobalExceptionHandler 将此异常映射为 HTTP 502
public class ExternalApiException extends RuntimeException {
    public ExternalApiException(String message) {
        super(message);
    }

    public ExternalApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
