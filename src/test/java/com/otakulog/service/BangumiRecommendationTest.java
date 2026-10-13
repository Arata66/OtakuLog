package com.otakulog.service;

import com.otakulog.service.impl.BangumiServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class BangumiRecommendationTest {
    BangumiServiceImpl service; MockRestServiceServer server;
    @BeforeEach void 准备虚构上游() {
        service = new BangumiServiceImpl(); var builder = RestClient.builder().baseUrl("https://api.bgm.tv");
        server = MockRestServiceServer.bindTo(builder).build(); ReflectionTestUtils.setField(service,"client",builder.build());
    }
    @Test void 当按标签和年份召回时应该发送官方过滤条件而非关键词伪匹配() throws Exception {
        server.expect(requestTo("https://api.bgm.tv/v0/search/subjects?limit=20")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"keyword\":\"\",\"sort\":\"heat\",\"filter\":{\"type\":[2],\"tag\":[\"机战\"],\"air_date\":[\">=2020-01-01\",\"<=2024-12-31\"]}}"))
                .andRespond(withSuccess("{\"data\":[]}",MediaType.APPLICATION_JSON));
        var method = assertDoesNotThrow(() -> service.getClass().getMethod("searchTaggedSubjects",String.class,int.class,Integer.class,Integer.class));
        assertEquals(java.util.List.of(), method.invoke(service,"机战",20,2020,2024)); server.verify();
    }
    @Test void 当排除标签位于展示前十个以外时应该仍保留完整核对标签与动画类型() throws Exception {
        String tags = java.util.stream.IntStream.range(0,12).mapToObj(i -> "{\"name\":\""+(i==11 ? "机战" : "标签"+i)+"\",\"count\":1}").collect(java.util.stream.Collectors.joining(","));
        server.expect(requestTo("https://api.bgm.tv/v0/subjects/99")).andRespond(withSuccess("{\"id\":99,\"type\":2,\"name\":\"虚构动画\",\"tags\":["+tags+"]}",MediaType.APPLICATION_JSON));
        var detail=service.getSubject(99); assertEquals(10,detail.getTags().size());
        var getter=assertDoesNotThrow(() -> detail.getClass().getMethod("getAllTags"));
        assertEquals(12,((java.util.List<?>) getter.invoke(detail)).size());
        assertTrue(((java.util.List<?>)getter.invoke(detail)).contains("机战"));
        assertEquals(2, detail.getClass().getMethod("getType").invoke(detail)); server.verify();
    }
}
