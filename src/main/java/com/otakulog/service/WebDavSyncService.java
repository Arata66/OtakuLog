package com.otakulog.service;

import java.util.Map;

// WebDAV 同步服务接口
public interface WebDavSyncService {
    Map<String, Object> push();

    Map<String, Object> pull();

    Map<String, Object> previewPull();

    Map<String, Object> pull(String fingerprint);

    Map<String, Object> getStatus();
}
