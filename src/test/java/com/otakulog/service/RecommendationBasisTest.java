package com.otakulog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.otakulog.dto.*;
import com.otakulog.entity.*;
import com.otakulog.enums.*;
import com.otakulog.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:recommendation_basis;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class RecommendationBasisTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc;
    @Autowired AnimeRepository anime; @Autowired AnimeMemoryRepository memories; @Autowired MemoryInsightRepository insights;
    @Autowired MemoryInsightService insightService; @Autowired AiQuotaRepository quota; @Autowired BackupService backup;
    @MockBean BangumiService bangumi;
    @BeforeEach void 准备虚构来源() {
        BackupTestQuota.seed(quota);
        for(String table:List.of("recommendation_basis","recommendation_shown","recommendation_feedback","recommendation_preference"))
            if(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME=?",Integer.class,table.toUpperCase(Locale.ROOT))>0)jdbc.update("DELETE FROM "+table);
        insights.deleteAll();memories.deleteAll();anime.deleteAll();
        when(bangumi.getSubject(anyInt())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            int id=call.getArgument(0);var d=new BangumiSubjectDetail();d.setId(id);d.setType(2);d.setName("虚构作品"+id);d.setAllTags(id==202?List.of("剧情","机战"):List.of("剧情","奇幻"));d.setAirDate("2020-01-01");d.setRating(8.0);return d;
        });
        when(bangumi.searchTaggedSubjects(anyString(),anyInt(),any(),any())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            var a=new BangumiResult();a.setId(201);var b=new BangumiResult();b.setId(202);return List.of(a,b);
        });
    }
    MemoryInsight source(InsightDirection direction,InsightStatus status) {
        var a=new Anime();a.setName("虚构来源"+UUID.randomUUID());a.setTotalEpisodes(12);a.setCurrentEpisode(0);a.setStatus(AnimeStatus.PLANNING);a=anime.saveAndFlush(a);
        var m=new AnimeMemory();m.setAnimeId(a.getId());m.setMemoryKey(UUID.randomUUID().toString());m.setContent("虚构原文：人物选择让我喜欢，也有不满意的段落。");m=memories.saveAndFlush(m);
        var i=insightService.create(a.getId(),m.getId(),new MemoryInsightDTO.WriteRequest(UUID.randomUUID().toString(),0L,InsightQuoteField.CONTENT,"人物选择","虚构的已核对观点",direction,InsightAspect.CHARACTER,"人物选择",status,null));
        return insights.findById(i.id()).orElseThrow();
    }
    ObjectNode getData(String url) throws Exception {return (ObjectNode)mapper.readTree(mvc.perform(get(url).with(user("tester"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");}
    ObjectNode write(String url,ObjectNode body) throws Exception {return (ObjectNode)mapper.readTree(mvc.perform(post(url).with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");}
    ObjectNode request(MemoryInsight i,String tag) throws Exception {
        var data=getData("/api/recommendations/bases");var src=data.path("sources").findValuesAsText("insightKey");assertTrue(src.contains(i.getInsightKey()));
        var current=java.util.stream.StreamSupport.stream(data.path("sources").spliterator(),false).filter(s -> s.path("insightKey").asText().equals(i.getInsightKey())).findFirst().orElseThrow();
        var body=mapper.createObjectNode();body.put("key",UUID.randomUUID().toString());body.put("insightKey",i.getInsightKey());body.put("sourceVersion",0);body.put("insightVersion",0);body.put("sourceFingerprint",current.path("sourceFingerprint").asText());body.put("direction",i.getDirection().name());body.put("kind","TAG");body.put("tag",tag);body.putNull("subjectId");body.put("enabled",true);body.put("expectedVersion",0);return body;
    }
    @Test void 当读取关联来源时应该只列当前确认观点且不写库或调用上游() throws Exception {
        var valid=source(InsightDirection.LIKE,InsightStatus.CONFIRMED);source(InsightDirection.DISLIKE,InsightStatus.REJECTED);source(InsightDirection.LIKE,InsightStatus.PENDING);
        var stale=source(InsightDirection.LIKE,InsightStatus.CONFIRMED);jdbc.update("UPDATE anime_memory SET version=1 WHERE id=?",stale.getMemoryId());
        var data=getData("/api/recommendations/bases");assertEquals(1,data.path("sources").size());assertEquals(valid.getInsightKey(),data.path("sources").get(0).path("insightKey").asText());verifyNoInteractions(bangumi);
    }
    @Test void 当预览并重复保存关联时应该预览零写入且只持久保存一条() throws Exception {
        var body=request(source(InsightDirection.LIKE,InsightStatus.CONFIRMED),"剧情");var before=backup.exportJson();
        var preview=write("/api/recommendations/bases/preview",body);assertTrue(preview.path("effect").asText().contains("剧情"));assertEquals(mapper.readTree(before).path("anime"),mapper.readTree(backup.exportJson()).path("anime"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_basis",Integer.class));
        var first=write("/api/recommendations/bases",body);assertEquals(first,write("/api/recommendations/bases",body));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_basis",Integer.class));
    }
    @Test void 当来源变化后保存旧预览时应该拒绝并保留零写入() throws Exception {
        var i=source(InsightDirection.LIKE,InsightStatus.CONFIRMED);var body=request(i,"剧情");write("/api/recommendations/bases/preview",body);
        jdbc.update("UPDATE anime_memory SET version=1 WHERE id=?",i.getMemoryId());
        mvc.perform(post("/api/recommendations/bases").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isConflict());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_basis",Integer.class));
    }
    @Test void 当数字标签或缺CSRF时应该拒绝关联写入() throws Exception {
        var body=request(source(InsightDirection.LIKE,InsightStatus.CONFIRMED),"剧情");
        mvc.perform(post("/api/recommendations/bases").with(user("tester")).contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isForbidden());
        body.put("tag",123);mvc.perform(post("/api/recommendations/bases").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isBadRequest());
    }
    @Test void 当停用关联后用旧版本修改时应该冲突且已停用状态保留() throws Exception {
        var body=request(source(InsightDirection.LIKE,InsightStatus.CONFIRMED),"剧情");write("/api/recommendations/bases",body);
        mvc.perform(delete("/api/recommendations/bases/"+body.path("key").asText()).param("expectedVersion","0").with(user("tester")).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/recommendations/bases").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isConflict());
        assertFalse(getData("/api/recommendations/bases").path("entries").get(0).path("data").path("enabled").asBoolean());
    }
    @Test void 当按标签找时应该核对实际标签而非把搜索结果当作依据() throws Exception {
        var result=getData("/api/recommendations?mode=TAGS&tags=剧情");assertEquals(2,result.path("entries").size());assertTrue(result.path("entries").get(0).path("reason").asText().contains("剧情"));
        assertEquals(0,getData("/api/recommendations?mode=TAGS&tags=不存在的标签").path("entries").size());
    }
    @Test void 当按作品找时应该使用真实标签且避开种子自身() throws Exception {
        var result=getData("/api/recommendations?mode=SEED&subjectId=201");assertEquals(1,result.path("entries").size());assertEquals(202,result.path("entries").get(0).path("id").asInt());
        assertEquals("虚构作品201",result.path("context").path("seedName").asText());
        mvc.perform(get("/api/recommendations").param("mode","SEED").param("subjectId","201").param("tags","机战").with(user("tester"))).andExpect(status().isBadRequest());
    }
    @Test void 当口味模式没有选中喜欢关联时应该不回退旧标签推荐() throws Exception {
        var result=getData("/api/recommendations?mode=TASTE");assertEquals("COLD_START_TASTE",result.path("status").asText());verifyNoInteractions(bangumi);
    }
    @Test void 当口味选中相同标签的喜欢与不喜欢时应该保留冲突且不请求候选() throws Exception {
        var like=write("/api/recommendations/bases",request(source(InsightDirection.LIKE,InsightStatus.CONFIRMED),"剧情"));
        var dislike=write("/api/recommendations/bases",request(source(InsightDirection.DISLIKE,InsightStatus.CONFIRMED),"剧情"));
        var result=getData("/api/recommendations?mode=TASTE&basisKeys="+like.path("data").path("key").asText()+","+dislike.path("data").path("key").asText());
        assertEquals("BASIS_CONFLICT",result.path("status").asText());assertEquals(2,result.path("context").path("bases").size());verifyNoInteractions(bangumi);
    }
    @Test void 当口味有正负不同标签时应该避开负面标签且标明确认观点依据() throws Exception {
        var like=write("/api/recommendations/bases",request(source(InsightDirection.LIKE,InsightStatus.CONFIRMED),"剧情"));
        var dislike=write("/api/recommendations/bases",request(source(InsightDirection.DISLIKE,InsightStatus.CONFIRMED),"机战"));
        var result=getData("/api/recommendations?mode=TASTE&basisKeys="+like.path("data").path("key").asText()+","+dislike.path("data").path("key").asText());
        assertEquals(1,result.path("entries").size());assertEquals(201,result.path("entries").get(0).path("id").asInt());assertTrue(result.path("entries").get(0).path("reason").asText().contains("人物选择"));
        assertEquals(1,result.path("excludedCounts").path("TASTE_TAG").asInt());
    }
    @Test void 当关联来源后来变化时应该暂停且拒绝旧批次反馈() throws Exception {
        var i=source(InsightDirection.LIKE,InsightStatus.CONFIRMED);var saved=write("/api/recommendations/bases",request(i,"剧情"));String key=saved.path("data").path("key").asText();
        var batch=getData("/api/recommendations?mode=TASTE&basisKeys="+key);
        jdbc.update("UPDATE memory_insight SET version=1,statement='虚构新观点' WHERE id=?",i.getId());
        assertEquals("STALE",getData("/api/recommendations/bases").path("entries").get(0).path("state").asText());
        mvc.perform(get("/api/recommendations").param("mode","TASTE").param("basisKeys",key).with(user("tester"))).andExpect(status().isConflict());
        var body=mapper.createObjectNode();body.put("requestKey",UUID.randomUUID().toString());body.put("batchKey",batch.path("batchKey").asText());body.put("subjectId",201);body.put("kind","NOT_INTERESTED");
        mvc.perform(post("/api/recommendations/feedback").with(user("tester")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isConflict());
    }
    @Test void 当完整备份恢复并重映射来源ID时应该保持关联有效且不外发() throws Exception {
        write("/api/recommendations/bases",request(source(InsightDirection.LIKE,InsightStatus.CONFIRMED),"剧情"));
        var exported=mapper.readTree(backup.exportJson());assertEquals(6,exported.path("version").asInt());assertEquals(1,exported.path("recommendationBases").size());
        jdbc.update("DELETE FROM recommendation_basis");insights.deleteAll();memories.deleteAll();anime.deleteAll();clearInvocations(bangumi);
        backup.importJson(exported.toString());backup.importJson(exported.toString());
        assertEquals(exported.path("recommendationBases"),mapper.readTree(backup.exportJson()).path("recommendationBases"));assertEquals("ACTIVE",getData("/api/recommendations/bases").path("entries").get(0).path("state").asText());verifyNoInteractions(bangumi);
    }
    @Test void 当作品关联与标签关联同时选中时应该让每条喜欢依据参与召回() throws Exception {
        var seed=new BangumiSubjectDetail();seed.setId(303);seed.setType(2);seed.setName("虚构种子303");seed.setAllTags(List.of("剧情","奇幻","科幻"));doReturn(seed).when(bangumi).getSubject(303);
        var first=request(source(InsightDirection.LIKE,InsightStatus.CONFIRMED),"剧情");first.put("kind","SEED");first.putNull("tag");first.put("subjectId",303);
        var seedBasis=write("/api/recommendations/bases",first);var tagBasis=write("/api/recommendations/bases",request(source(InsightDirection.LIKE,InsightStatus.CONFIRMED),"机战"));
        var result=getData("/api/recommendations?mode=TASTE&basisKeys="+seedBasis.path("data").path("key").asText()+","+tagBasis.path("data").path("key").asText());
        assertTrue(result.path("seedTags").toString().contains("机战"));assertEquals(2,result.path("context").path("bases").size());
    }
    @Test void 当恢复同编号不同原文的来源时应该不复活旧关联() throws Exception {
        write("/api/recommendations/bases",request(source(InsightDirection.LIKE,InsightStatus.CONFIRMED),"剧情"));
        var exported=(ObjectNode)mapper.readTree(backup.exportJson());((ObjectNode)exported.path("memories").get(0)).put("content","虚构原文：人物选择出现在完全不同的语境里。");
        insights.deleteAll();memories.deleteAll();anime.deleteAll();backup.importJson(exported.toString());
        assertEquals("STALE",getData("/api/recommendations/bases").path("entries").get(0).path("state").asText());verifyNoInteractions(bangumi);
    }
    @Test void 当旧版恢复或新关联同编号差异时应该保留本地且拒绝冲突() throws Exception {
        write("/api/recommendations/bases",request(source(InsightDirection.LIKE,InsightStatus.CONFIRMED),"剧情"));
        var exported=(ObjectNode)mapper.readTree(backup.exportJson());var old=exported.deepCopy();old.put("version",5);old.remove("recommendationBases");backup.importJson(old.toString());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_basis",Integer.class));
        var bad=exported.deepCopy();((ObjectNode)bad.path("recommendationBases").get(0)).put("tag","机战");assertEquals(false,backup.previewJson(bad.toString()).get("valid"));assertThrows(IllegalArgumentException.class,() -> backup.importJson(bad.toString()));
        ((ObjectNode)bad.path("recommendationBases").get(0)).put("key",UUID.randomUUID().toString());assertEquals(false,backup.previewJson(bad.toString()).get("valid"));verifyNoInteractions(bangumi);
    }
}
