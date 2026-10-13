package com.otakulog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.otakulog.dto.BangumiResult;
import com.otakulog.dto.BangumiSubjectDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:recommendations;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class RecommendationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc;
    @Autowired BackupService backup;
    @Autowired com.otakulog.repository.AiQuotaRepository quota;
    @MockBean BangumiService bangumi;
    @BeforeEach void 准备虚构候选() {
        BackupTestQuota.seed(quota);
        for (String table : List.of("recommendation_shown","recommendation_feedback","recommendation_preference"))
            if (jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME=?",Integer.class,table.toUpperCase(java.util.Locale.ROOT)) > 0) jdbc.update("DELETE FROM "+table);
        when(bangumi.searchTaggedSubjects(anyString(),anyInt(),any(),any())).thenReturn(List.of(row(101),row(102),row(103),row(104)));
        when(bangumi.getSubject(anyInt())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            int id=call.getArgument(0); var detail=new BangumiSubjectDetail(); detail.setId(id);detail.setType(2);detail.setName("虚构候选"+id);
            detail.setAllTags(id==102 ? List.of("剧情","机战") : List.of("剧情")); detail.setAirDate(id==103 ? "2010-01-01" : id==104 ? null : "2020-01-01"); detail.setRating(8.0); return detail;
        });
    }
    BangumiResult row(int id) { var value=new BangumiResult();value.setId(id);value.setName("虚构搜索"+id);return value; }
    ObjectNode settings() throws Exception {
        return (ObjectNode)mapper.readTree(mvc.perform(get("/api/recommendations/settings").with(user("tester"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
    }
    ObjectNode save(ObjectNode value) throws Exception {
        return (ObjectNode)mapper.readTree(mvc.perform(put("/api/recommendations/settings").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(value.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
    }
    void seed() throws Exception { var value=settings();value.putArray("recallTags").add("剧情");save(value); }
    ObjectNode recommendations(boolean recent) throws Exception {
        return (ObjectNode)mapper.readTree(mvc.perform(get("/api/recommendations").param("includeRecent",String.valueOf(recent)).with(user("tester"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
    }
    @Test void 当没有评分依据或主动标签时应该提示冷启动且不调用上游() throws Exception {
        var result=recommendations(false);assertEquals("COLD_START",result.path("status").asText());verifyNoInteractions(bangumi);
    }
    @Test void 当保存年份和排除标签时应该持久保留并只返回符合硬条件的候选() throws Exception {
        var value=settings();value.put("minYear",2020);value.put("maxYear",2020);value.putArray("recallTags").add("剧情");value.putArray("excludedTags").add("机战");save(value);
        assertEquals(2020,settings().path("minYear").asInt());
        var result=recommendations(false);assertEquals(1,result.path("entries").size());assertEquals(101,result.path("entries").get(0).path("id").asInt());
        assertEquals(1,result.path("excludedCounts").path("TAG").asInt());assertEquals(1,result.path("excludedCounts").path("UNKNOWN_YEAR").asInt());
    }
    @Test void 当旧窗口覆盖条件或缺少CSRF时应该拒绝且保留已保存条件() throws Exception {
        var old=settings();var first=old.deepCopy();first.putArray("excludedTags").add("机战");save(first);
        mvc.perform(put("/api/recommendations/settings").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(old.toString())).andExpect(status().isConflict());
        mvc.perform(put("/api/recommendations/settings").with(user("tester")).contentType(MediaType.APPLICATION_JSON).content(old.toString())).andExpect(status().isForbidden());
        assertEquals("机战",settings().path("excludedTags").get(0).asText());
    }
    @Test void 当年份逆序或标签格式非法时应该零写入并说明校验失败() throws Exception {
        var value=settings();value.put("minYear",2024);value.put("maxYear",2020);
        mvc.perform(put("/api/recommendations/settings").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(value.toString())).andExpect(status().isBadRequest());
        value=settings();value.putArray("excludedTags").add(123);
        mvc.perform(put("/api/recommendations/settings").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(value.toString())).andExpect(status().isBadRequest());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_preference",Integer.class));
    }
    @Test void 当反馈响应丢失后重试再撤销时应该防重且不更改观看记录() throws Exception {
        seed();var batch=recommendations(false);String key=UUID.randomUUID().toString();
        String body=mapper.writeValueAsString(Map.of("requestKey",key,"batchKey",batch.path("batchKey").asText(),"subjectId",101,"kind","NOT_INTERESTED"));
        for(int i=0;i<2;i++)mvc.perform(post("/api/recommendations/feedback").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_feedback",Integer.class));
        assertEquals(3,recommendations(false).path("entries").size());
        mvc.perform(delete("/api/recommendations/feedback/"+key).param("expectedVersion","0").with(user("tester")).with(csrf())).andExpect(status().isOk());
        assertEquals(4,recommendations(false).path("entries").size());
        mvc.perform(post("/api/recommendations/feedback").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM anime",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM episode_record",Integer.class));
    }
    @Test void 当作品被标记已看时应该默认排除且显式允许重看后带标记返回() throws Exception {
        seed();var batch=recommendations(false);
        String body=mapper.writeValueAsString(Map.of("requestKey",UUID.randomUUID().toString(),"batchKey",batch.path("batchKey").asText(),"subjectId",101,"kind","WATCHED"));
        mvc.perform(post("/api/recommendations/feedback").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertEquals(3,recommendations(false).path("entries").size());var value=settings();value.put("includeTracked",true);save(value);
        var item=recommendations(false).path("entries").get(0);assertEquals(101,item.path("id").asInt());assertTrue(item.path("watched").asBoolean());
    }
    @Test void 当同一批次先后反馈不同作品时应该都允许且每条只保存一次() throws Exception {
        seed();var batch=recommendations(false);
        for(int id:List.of(101,102)){
            String body=mapper.writeValueAsString(Map.of("requestKey",UUID.randomUUID().toString(),"batchKey",batch.path("batchKey").asText(),"subjectId",id,"kind","NOT_INTERESTED"));
            mvc.perform(post("/api/recommendations/feedback").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        }
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_feedback",Integer.class));
    }
    @Test void 当界面确认展示后应该记录近期且不因普通读取提前去重() throws Exception {
        seed();var batch=recommendations(false);assertEquals(4,recommendations(false).path("entries").size());
        String body=mapper.writeValueAsString(Map.of("batchKey",batch.path("batchKey").asText(),"subjectIds",List.of(101,102,103,104)));
        for(int i=0;i<2;i++)mvc.perform(post("/api/recommendations/shown").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertEquals(0,recommendations(false).path("entries").size());assertEquals(4,recommendations(true).path("entries").size());
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_shown",Integer.class));
    }
    @Test void 当其他身份或新条件确认旧批次时应该拒绝记录() throws Exception {
        seed();var batch=recommendations(false);String body=mapper.writeValueAsString(Map.of("batchKey",batch.path("batchKey").asText(),"subjectIds",List.of(101)));
        mvc.perform(post("/api/recommendations/shown").with(user("other")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        var value=settings();value.put("minYear",2021);save(value);
        mvc.perform(post("/api/recommendations/shown").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_shown",Integer.class));
    }
    @Test void 当最近展示超过七天时应该重新纳入候选() throws Exception {
        seed();var batch=recommendations(false);String body=mapper.writeValueAsString(Map.of("batchKey",batch.path("batchKey").asText(),"subjectIds",List.of(101)));
        mvc.perform(post("/api/recommendations/shown").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertEquals(3,recommendations(false).path("entries").size());
        jdbc.update("UPDATE recommendation_shown SET shown_at=?",java.time.LocalDateTime.now().minusDays(8));assertEquals(4,recommendations(false).path("entries").size());
    }
    @Test void 当部分详情无法核对时应该减少结果并说明未核对数量而全部失败应报错() throws Exception {
        seed();when(bangumi.getSubject(102)).thenReturn(null);var result=recommendations(false);assertEquals(3,result.path("entries").size());assertEquals(1,result.path("unverified").asInt());
        when(bangumi.getSubject(anyInt())).thenReturn(null);mvc.perform(get("/api/recommendations").with(user("tester"))).andExpect(status().isBadGateway());
    }
    @Test void 当全部召回失败或正常返回空候选时应该区别错误和空状态() throws Exception {
        seed();when(bangumi.searchTaggedSubjects(anyString(),anyInt(),any(),any())).thenThrow(new com.otakulog.common.ExternalApiException("虚构上游失败"));
        mvc.perform(get("/api/recommendations").with(user("tester"))).andExpect(status().isBadGateway());
        doReturn(List.of()).when(bangumi).searchTaggedSubjects(anyString(),anyInt(),any(),any());assertEquals("EMPTY",recommendations(false).path("status").asText());
    }
    @Test void 当完整备份恢复推荐状态时应该保留条件与撤销历史且不恢复近期缓存或外发() throws Exception {
        seed();var batch=recommendations(false);String key=UUID.randomUUID().toString();
        String body=mapper.writeValueAsString(Map.of("requestKey",key,"batchKey",batch.path("batchKey").asText(),"subjectId",101,"kind","NOT_INTERESTED"));
        mvc.perform(post("/api/recommendations/feedback").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(delete("/api/recommendations/feedback/"+key).param("expectedVersion","0").with(user("tester")).with(csrf())).andExpect(status().isOk());
        var current=recommendations(false);String shownBody=mapper.writeValueAsString(Map.of("batchKey",current.path("batchKey").asText(),"subjectIds",List.of(101)));
        mvc.perform(post("/api/recommendations/shown").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(shownBody)).andExpect(status().isOk());
        var document=mapper.readTree(backup.exportJson());assertEquals(6,document.path("version").asInt());assertEquals(1,document.path("recommendationPreferences").size());assertEquals(1,document.path("recommendationFeedback").size());assertFalse(document.has("recommendationShown"));
        for(String table:List.of("recommendation_shown","recommendation_feedback","recommendation_preference"))jdbc.update("DELETE FROM "+table);
        clearInvocations(bangumi);backup.importJson(document.toString());verifyNoInteractions(bangumi);
        var restored=mapper.readTree(backup.exportJson());assertEquals(document.path("recommendationPreferences"),restored.path("recommendationPreferences"));assertEquals(document.path("recommendationFeedback"),restored.path("recommendationFeedback"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_shown",Integer.class));
        mvc.perform(post("/api/recommendations/feedback").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false));
    }
    @Test void 当旧版备份恢复或同编号条件不同时时应该保留新状态并拒绝冲突() throws Exception {
        seed();var document=(ObjectNode)mapper.readTree(backup.exportJson());
        var old=document.deepCopy();old.put("version",4);old.remove(List.of("recommendationPreferences","recommendationFeedback","recommendationBases"));backup.importJson(old.toString());
        assertEquals(1,settings().path("recallTags").size());
        ((ObjectNode)document.path("recommendationPreferences").get(0).path("settings")).put("minYear",2020);
        assertEquals(false,backup.previewJson(document.toString()).get("valid"));assertThrows(IllegalArgumentException.class,() -> backup.importJson(document.toString()));
        assertTrue(settings().path("minYear").isNull());
    }
}
