package com.otakulog.service;

import com.otakulog.common.ExternalApiException;
import com.otakulog.service.impl.TraceMoeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TraceMoeServiceTest {
    private TraceMoeServiceImpl service;
    private MockRestServiceServer server;

    private static final String MATCH = """
            {"filename":"星港巡游 - 01.mp4","episode":1,"from":10.5,"to":12.0,
             "similarity":0.944,"image":"https://example.test/frame.jpg","video":"https://example.test/clip.mp4"}
            """;

    @BeforeEach
    void 配置模拟识别服务() {
        service = new TraceMoeServiceImpl();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.trace.moe");
        server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(service, "client", builder.build());
    }

    @Test
    void 当上传普通大小截图时应该通过请求体识别并返回候选作品() {
        byte[] bytes = new byte[64 * 1024];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        server.expect(requestTo("https://api.trace.moe/search")).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.IMAGE_PNG)).andExpect(content().bytes(bytes))
                .andRespond(withSuccess("{\"error\":\"\",\"result\":[" + MATCH + "]}", MediaType.APPLICATION_JSON));

        Map<String, Object> result = service.searchByImage(new MockMultipartFile("image", "截图.png", "image/png", bytes));

        assertEquals("星港巡游", result.get("animeName"));
        assertEquals(1, result.get("episode"));
        assertEquals(94L, result.get("confidence"));
        assertEquals(10.5, result.get("from"));
        assertEquals(12.0, result.get("to"));
        assertEquals("https://example.test/frame.jpg", result.get("image"));
        assertEquals("https://example.test/clip.mp4", result.get("video"));
        assertEquals(1, ((List<?>) result.get("allResults")).size());
        server.verify();
    }

    @Test
    void 当截图缺少媒体类型时应该仍能上传识别() {
        byte[] bytes = {1, 2, 3};
        server.expect(requestTo("https://api.trace.moe/search")).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM)).andExpect(content().bytes(bytes))
                .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));
        assertNull(service.searchByImage(new MockMultipartFile("image", "截图", null, bytes)));
        server.verify();
    }

    @Test
    void 当返回超过五个候选时应该只展示前五个() {
        server.expect(requestTo("https://api.trace.moe/search")).andRespond(withSuccess(
                "{\"result\":[" + String.join(",", java.util.Collections.nCopies(6, MATCH)) + "]}", MediaType.APPLICATION_JSON));
        var result = service.searchByImage(new MockMultipartFile("image", "截图.png", "image/png", new byte[]{1}));
        assertEquals(5, ((List<?>) result.get("allResults")).size());
        server.verify();
    }

    @Test
    void 当识别额度耗尽时应该报告外部服务失败() {
        server.expect(requestTo("https://api.trace.moe/search"))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"Search quota depleted\"}"));
        assertThrows(ExternalApiException.class, () -> service.searchByImage(
                new MockMultipartFile("image", "截图.png", "image/png", new byte[]{1})));
        server.verify();
    }

    @Test
    void 当识别服务无法连接时应该报告外部服务失败() {
        server.expect(requestTo("https://api.trace.moe/search")).andRespond(withException(new IOException("连接失败")));
        assertThrows(ExternalApiException.class, () -> service.searchByImage(
                new MockMultipartFile("image", "截图.png", "image/png", new byte[]{1})));
        server.verify();
    }
}
