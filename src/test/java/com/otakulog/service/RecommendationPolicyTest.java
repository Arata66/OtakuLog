package com.otakulog.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RecommendationPolicyTest {
    String reason(Integer year, List<String> tags, Integer min, Integer max, List<String> excluded,
            boolean tracked, boolean watched, boolean rejected, boolean includeTracked, boolean recent, boolean includeRecent) throws Exception {
        var policy = assertDoesNotThrow(() -> Class.forName("com.otakulog.service.RecommendationPolicy"));
        var method = policy.getMethod("evaluate", Integer.class, List.class, Integer.class, Integer.class, List.class,
                boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);
        return (String) method.invoke(null, year, tags, min, max, excluded, tracked, watched, rejected, includeTracked, recent, includeRecent);
    }
    @Test void 当年份位于包含端点的范围时应该保留候选() throws Exception {
        for (int year : List.of(2020, 2024)) assertNull(reason(year,List.of("剧情"),2020,2024,List.of(),false,false,false,false,false,false));
        assertEquals("YEAR", reason(2019,List.of(),2020,2024,List.of(),false,false,false,false,false,false));
    }
    @Test void 当限定年份但首播年份未知时应该排除且不猜年份() throws Exception {
        assertEquals("UNKNOWN_YEAR", reason(null,List.of(),2020,null,List.of(),false,false,false,false,false,false));
        assertNull(reason(null,List.of(),null,null,List.of(),false,false,false,false,false,false));
    }
    @Test void 当排除标签匹配规范化名称时应该排除且不扩展别名或子串() throws Exception {
        assertEquals("TAG", reason(2024,List.of(" Mecha "),null,null,List.of("ｍｅｃｈａ"),false,false,false,false,false,false));
        assertNull(reason(2024,List.of("科幻","机战音乐"),null,null,List.of("机战"),false,false,false,false,false,false));
        assertEquals("UNVERIFIED_TAGS", reason(2024,null,null,null,List.of("机战"),false,false,false,false,false,false));
    }
    @Test void 当用户明确不感兴趣时应该始终排除且不能被开关绕过() throws Exception {
        assertEquals("FEEDBACK", reason(2024,List.of(),null,null,List.of(),true,true,true,true,true,true));
    }
    @Test void 当库内或已看作品被显式允许时应该纳入且保持其他条件() throws Exception {
        assertEquals("TRACKED", reason(2024,List.of(),null,null,List.of(),true,false,false,false,false,false));
        assertEquals("WATCHED", reason(2024,List.of(),null,null,List.of(),false,true,false,false,false,false));
        assertNull(reason(2024,List.of(),null,null,List.of(),true,true,false,true,false,false));
        assertEquals("YEAR", reason(2010,List.of(),2020,null,List.of(),true,true,false,true,false,false));
    }
    @Test void 当候选近期展示过时应该默认排除且只能明确打开近期查看() throws Exception {
        assertEquals("RECENT", reason(2024,List.of(),null,null,List.of(),false,false,false,false,true,false));
        assertNull(reason(2024,List.of(),null,null,List.of(),false,false,false,false,true,true));
    }
}
