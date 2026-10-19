package com.otakulog.service;

import com.otakulog.common.ConflictException;
import com.otakulog.common.ResourceNotFoundException;
import com.otakulog.dto.AnimeVO;
import com.otakulog.dto.BangumiResult;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.util.AnimeVOMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BangumiMatchStore {
    private final AnimeRepository anime;
    public BangumiMatchStore(AnimeRepository anime) { this.anime = anime; }
    @Transactional(readOnly = true)
    public AnimeVO read(Long id) {
        return AnimeVOMapper.toVO(anime.findById(id).orElseThrow(() -> new ResourceNotFoundException("未找到该番剧")));
    }
    @Transactional
    public AnimeVO apply(Long id, String expectedName, BangumiResult match) {
        var current = anime.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("未找到该番剧"));
        if (current.getBangumiId() != null) return AnimeVOMapper.toVO(current);
        if (!java.util.Objects.equals(current.getName(), expectedName)) throw new ConflictException("作品名称已变化，请重新匹配 Bangumi");
        current.setBangumiId(match.getId());
        if (current.getCoverUrl() == null && match.getImage() != null) current.setCoverUrl(match.getImage());
        return AnimeVOMapper.toVO(anime.save(current));
    }
}
