package com.otakulog.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

// 以图搜番服务接口
public interface TraceMoeService {
    Map<String, Object> searchByImage(MultipartFile image);
}
