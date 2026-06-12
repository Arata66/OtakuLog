package com.otakulog.common;

// 资源不存在异常，用于 delete/update 时找不到目标资源
// GlobalExceptionHandler 将此异常映射为 HTTP 404
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
