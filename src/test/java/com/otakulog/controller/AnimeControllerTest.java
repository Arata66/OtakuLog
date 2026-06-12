package com.otakulog.controller;

import com.otakulog.dto.AnimeVO;
import com.otakulog.service.AnimeService;
import com.otakulog.service.AiringScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnimeControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AnimeService animeService;

    @MockBean
    private AiringScheduleService airingScheduleService;

    @Test
    void searchAnime_shouldReturnResults() throws Exception {
        when(animeService.searchAnime(any(), any(), any(), any()))
                .thenReturn(java.util.List.of());

        mvc.perform(get("/api/anime/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void searchAnimePaged_shouldReturnPage() throws Exception {
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
    void deleteAnime_shouldReturnSuccess() throws Exception {
        mvc.perform(delete("/api/anime/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void batchUpdateStatus_invalidStatus_shouldReturn400() throws Exception {
        mvc.perform(post("/api/anime/batch-status")
                        .contentType("application/json")
                        .content("{\"ids\":[1],\"status\":\"INVALID\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateStatus_invalidStatus_shouldReturn400() throws Exception {
        mvc.perform(post("/api/anime/1/status")
                        .param("status", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nextEpisode_shouldReturnUpdated() throws Exception {
        AnimeVO vo = new AnimeVO();
        vo.setId(1L);
        vo.setCurrentEpisode(3);
        when(animeService.nextEpisode(1L)).thenReturn(vo);

        mvc.perform(post("/api/anime/1/next-episode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentEpisode").value(3));
    }

    @Test
    void prevEpisode_shouldReturnUpdated() throws Exception {
        AnimeVO vo = new AnimeVO();
        vo.setId(1L);
        vo.setCurrentEpisode(1);
        when(animeService.prevEpisode(1L)).thenReturn(vo);

        mvc.perform(post("/api/anime/1/prev-episode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentEpisode").value(1));
    }

    @Test
    void getStats_shouldReturnStats() throws Exception {
        when(animeService.getStats()).thenReturn(java.util.Map.of("total", 5L));

        mvc.perform(get("/api/anime/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
