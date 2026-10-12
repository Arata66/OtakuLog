package com.otakulog.dto;

import java.util.List;
import java.util.Map;

public record BangumiCollectionSnapshot(int total, List<Map<String, Object>> rows, boolean sourceChanged) {}
