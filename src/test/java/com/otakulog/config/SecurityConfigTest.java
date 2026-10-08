package com.otakulog.config;

import com.otakulog.dto.AnimeVO;
import com.otakulog.service.AiringScheduleService;
import com.otakulog.service.AnimeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AnimeService animeService;

    @MockBean
    private AiringScheduleService airingScheduleService;

    @ParameterizedTest
    @ValueSource(strings = {"/api/anime/stats", "/api/anime/export", "/api/groups",
            "/api/tags", "/api/report/annual/latest", "/api/sync/status", "/api/bangumi/search"})
    void 当未登录读取API时应该返回JSON未认证错误(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(animeService, airingScheduleService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/anime/1/next-episode", "/api/anime/import", "/api/sync/push"})
    void 当未登录写入API时应该拒绝且不执行业务(String path) throws Exception {
        mvc.perform(post(path).contentType("application/json").content("[]"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(animeService, airingScheduleService);
    }

    @Test
    void 当未登录携带合法令牌删除时应该仍然拒绝() throws Exception {
        mvc.perform(delete("/api/anime/1").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(animeService);
    }

    @Test
    void 当登录用户读取API时应该成功并禁止HTTP缓存() throws Exception {
        when(animeService.getStats()).thenReturn(Map.of("total", 2));
        mvc.perform(get("/api/anime/stats").with(user("test")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    void 当登录用户未带令牌写入时应该返回JSON禁止错误() throws Exception {
        mvc.perform(post("/api/anime/1/next-episode").with(user("test")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(animeService);
    }

    @Test
    void 当登录用户使用错误令牌写入时应该拒绝() throws Exception {
        mvc.perform(delete("/api/anime/1").with(user("test")).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(animeService);
    }

    @Test
    void 当登录用户带合法令牌写入时应该成功() throws Exception {
        AnimeVO vo = new AnimeVO();
        vo.setCurrentEpisode(2);
        when(animeService.nextEpisode(1L)).thenReturn(vo);
        mvc.perform(post("/api/anime/1/next-episode").with(user("test")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentEpisode").value(2));
    }

    @Test
    void 当匿名访问主页时应该跳转登录页() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/swagger-ui.html", "/v3/api-docs"})
    void 当匿名访问API文档时应该要求登录(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().is3xxRedirection());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login", "/css/anime.css", "/js/anime-app.js",
            "/manifest.json", "/sw.js", "/icons/icon-192.png"})
    void 当匿名访问登录页和必要静态资源时应该可用(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test
    void 当使用页面真实令牌登录和退出时应该完成会话闭环() throws Exception {
        MockHttpSession session = login("test");
        MvcResult page = mvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf_header\"")))
                .andReturn();
        String html = page.getResponse().getContentAsString();
        String token = extract(html, "<meta name=\"_csrf\" content=\"([^\"]+)\"");
        String headerName = extract(html, "<meta name=\"_csrf_header\" content=\"([^\"]+)\"");

        mvc.perform(post("/api/anime/1/next-episode").session(session).header(headerName, token))
                .andExpect(status().isOk());
        mvc.perform(post("/logout").session(session).param("_csrf", extractFormToken(html)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/anime/stats").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 当使用错误密码登录时应该返回登录错误页() throws Exception {
        MvcResult page = mvc.perform(get("/login")).andReturn();
        String token = extractFormToken(page.getResponse().getContentAsString());
        mvc.perform(post("/login").session((MockHttpSession) page.getRequest().getSession(false))
                        .param("_csrf", token)
                        .param("username", "test").param("password", "wrong"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void 当登录或退出未携带令牌时应该拒绝() throws Exception {
        mvc.perform(post("/login").param("username", "test").param("password", "test"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/logout").with(user("test")))
                .andExpect(status().isForbidden());
    }

    private MockHttpSession login(String password) throws Exception {
        MvcResult page = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
        String token = extractFormToken(page.getResponse().getContentAsString());
        MvcResult result = mvc.perform(post("/login")
                        .session((MockHttpSession) page.getRequest().getSession(false))
                        .param("_csrf", token)
                        .param("username", "test").param("password", password))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String extractFormToken(String html) {
        return extract(html, "name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    }

    private String extract(String html, String pattern) {
        var matcher = Pattern.compile(pattern).matcher(html);
        assertThat(matcher.find()).as("页面应包含可用的 CSRF 令牌").isTrue();
        return matcher.group(1);
    }
}
