package com.otakulog.util;

import com.otakulog.entity.AnimeMemory;
import com.otakulog.entity.MemoryInsight;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class InsightSourceFingerprint {
    private InsightSourceFingerprint() {}
    public static String of(AnimeMemory m, MemoryInsight i) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            // 只使用稳定身份和语义内容，恢复后自增ID变化不能误判来源。
            for(Object value:Arrays.asList(m.getMemoryKey(),m.getVersion(),m.getContent(),m.getLiked(),m.getDisliked(),m.getScope(),m.getContext(),m.getWatchedDate(),
                    i.getInsightKey(),i.getSourceVersion(),i.getVersion(),i.getQuoteField(),i.getQuote(),i.getStatement(),i.getDirection(),i.getAspect(),i.getFactor(),i.getStatus())) {
                String text=value==null?"N":value.toString();String part=value==null?"N;":"S"+text.length()+":"+text;
                digest.update(part.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(Exception e) { throw new IllegalStateException("观点来源核对失败",e); }
    }
}
