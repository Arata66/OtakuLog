package com.otakulog.service;

import com.otakulog.common.ExternalApiException;
import com.otakulog.service.impl.BangumiServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class BangumiCollectionsTest {
    private BangumiServiceImpl service;
    private MockRestServiceServer server;
    @BeforeEach
    void 配置收藏模拟服务() {
        service = new BangumiServiceImpl();
        var builder = RestClient.builder().baseUrl("https://api.bgm.tv");
        server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(service, "client", builder.build());
    }
    @Test
    void 当收藏超过一页时应该读取下一页且保留字段和封面协议() {
        server.expect(requestTo(url(50, 0))).andRespond(withSuccess(page(Collections.nCopies(50, row(1))), MediaType.APPLICATION_JSON));
        server.expect(requestTo(url(50, 50))).andRespond(withSuccess(page(Collections.singletonList(row(2))), MediaType.APPLICATION_JSON));
        var items = service.getUserCollections("test", 200);
        assertEquals(51, items.size()); assertEquals(2, items.get(50).get("subjectId"));
        assertEquals("收藏样例", items.get(0).get("nameCn")); assertEquals(3, items.get(0).get("epStatus"));
        assertEquals("https://example.test/cover.jpg", items.get(0).get("image")); server.verify();
    }
    @Test
    void 当达到请求上限时应该截断并停止分页() {
        server.expect(requestTo(url(2, 0))).andRespond(withSuccess(page(Collections.nCopies(3, row(1))), MediaType.APPLICATION_JSON));
        assertEquals(2, service.getUserCollections("test", 2).size()); server.verify();
    }
    @Test
    void 当上游返回明确空收藏时应该返回空列表() {
        server.expect(requestTo(url(50, 0))).andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        assertTrue(service.getUserCollections("test", 200).isEmpty()); server.verify();
    }
    @Test
    void 当上游响应缺失或无效时应该报外部失败而不是伪装为空收藏() {
        server.expect(requestTo(url(50, 0))).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThrows(ExternalApiException.class, () -> service.getUserCollections("test", 200)); server.verify();
    }
    @Test
    void 当后续页读取失败时应该不返回部分收藏作为成功结果() {
        server.expect(requestTo(url(50, 0))).andRespond(withSuccess(page(Collections.nCopies(50, row(1))), MediaType.APPLICATION_JSON));
        server.expect(requestTo(url(50, 50))).andRespond(withServerError());
        assertThrows(RuntimeException.class, () -> service.getUserCollections("test", 200)); server.verify();
    }
    private String url(int size, int offset) { return "https://api.bgm.tv/v0/users/test/collections?subject_type=2&limit=" + size + "&offset=" + offset; }
    private String page(java.util.List<String> rows) { return "{\"data\":[" + String.join(",", rows) + "]}"; }
    private String row(int id) { return "{\"subject\":{\"id\":" + id + ",\"name\":\"原名\",\"name_cn\":\"收藏样例\",\"eps\":12,\"images\":{\"large\":\"//example.test/cover.jpg\"}},\"type\":3,\"ep_status\":3}"; }
}
