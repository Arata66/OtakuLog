package com.otakulog.controller;

import com.otakulog.dto.AnimeVO;
import com.otakulog.service.AnimeService;
import com.otakulog.service.AiringScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "test")
class AnimeControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AnimeService animeService;

    @MockBean
    private AiringScheduleService airingScheduleService;

    @Test
    void 当搜索番剧时应该返回结果() throws Exception {
        when(animeService.searchAnime(any(), any(), any(), any()))
                .thenReturn(java.util.List.of());

        mvc.perform(get("/api/anime/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void 当分页搜索时应该返回分页结果() throws Exception {
        // Spring PageImpl 序列化会失败（Unpaged.getOffset），改用可序列化分页
        var pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        when(animeService.searchAnimePaged(isNull(), isNull(), any(), isNull()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        java.util.List.of(), pageable, 0));

        mvc.perform(get("/api/anime/page?page=0&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void 当删除番剧时应该返回成功() throws Exception {
        mvc.perform(delete("/api/anime/1").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void 当批量状态非法时应该返回参数错误() throws Exception {
        mvc.perform(post("/api/anime/batch-status").with(csrf())
                        .contentType("application/json")
                        .content("{\"ids\":[1],\"status\":\"INVALID\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 当单个状态非法时应该返回参数错误() throws Exception {
        mvc.perform(post("/api/anime/1/status").with(csrf())
                        .param("status", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 当记录下一集时应该返回更新后的进度() throws Exception {
        AnimeVO vo = new AnimeVO();
        vo.setId(1L);
        vo.setCurrentEpisode(3);
        when(animeService.nextEpisode(1L)).thenReturn(vo);

        mvc.perform(post("/api/anime/1/next-episode").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentEpisode").value(3));
    }

    @Test
    void 当撤销上一集时应该返回更新后的进度() throws Exception {
        AnimeVO vo = new AnimeVO();
        vo.setId(1L);
        vo.setCurrentEpisode(1);
        when(animeService.prevEpisode(1L)).thenReturn(vo);

        mvc.perform(post("/api/anime/1/prev-episode").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentEpisode").value(1));
    }

    @Test
    void 当查询统计时应该返回统计数据() throws Exception {
        when(animeService.getStats()).thenReturn(java.util.Map.of("total", 5L));

        mvc.perform(get("/api/anime/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void 当匹配Bangumi成功时应该返回关联结果() throws Exception {
        AnimeVO vo = new AnimeVO();
        vo.setId(1L);
        vo.setBangumiId(1001);
        when(animeService.matchBangumi(1L)).thenReturn(vo);

        mvc.perform(post("/api/anime/1/match-bangumi").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.bangumiId").value(1001));
    }

    @Test
    void 当匹配Bangumi失败时应该返回参数错误() throws Exception {
        when(animeService.matchBangumi(1L)).thenThrow(new IllegalArgumentException("未在 Bangumi 找到匹配结果"));

        mvc.perform(post("/api/anime/1/match-bangumi").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("未在 Bangumi 找到匹配结果"));
    }

    @Test
    void 当批量匹配Bangumi时应该返回处理摘要() throws Exception {
        when(animeService.batchMatchBangumi()).thenReturn(java.util.Map.of(
                "matched", 2,
                "failed", 1,
                "total", 3
        ));

        mvc.perform(post("/api/anime/batch-match-bangumi").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.matched").value(2))
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.total").value(3));
    }

    @Test
    void 当查询推荐时应该返回推荐列表() throws Exception {
        when(animeService.getRecommendations()).thenReturn(java.util.List.of(
                java.util.Map.of("id", 1001, "name", "推荐番剧", "reason", "标签: 奇幻")
        ));

        mvc.perform(get("/api/anime/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(1001));
    }

    @Test
    void 当查询热力图时应该返回日期记录() throws Exception {
        when(animeService.getHeatmap()).thenReturn(java.util.Map.of("2026-06-12", 2));

        mvc.perform(get("/api/anime/heatmap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data['2026-06-12']").value(2));
    }
}
