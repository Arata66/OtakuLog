package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.dto.AnimeGroupDTO;
import com.otakulog.dto.AnimeVO;
import com.otakulog.entity.AnimeGroup;
import com.otakulog.repository.AnimeGroupRepository;
import com.otakulog.service.AnimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "分组管理", description = "番剧分组CRUD")
@RestController
public class GroupController {

    private final AnimeGroupRepository groupRepository;
    private final AnimeService animeService;

    public GroupController(AnimeGroupRepository groupRepository, AnimeService animeService) {
        this.groupRepository = groupRepository;
        this.animeService = animeService;
    }

    @Operation(summary = "获取所有分组")
    @GetMapping("/api/groups")
    public ResponseEntity<ApiResponse<List<AnimeGroupDTO>>> getAllGroups() {
        List<AnimeGroup> groups = groupRepository.findAllByOrderBySortOrderAsc();
        List<AnimeGroupDTO> dtos = groups.stream().map(g -> {
            AnimeGroupDTO dto = new AnimeGroupDTO();
            dto.setId(g.getId());
            dto.setName(g.getName());
            dto.setDescription(g.getDescription());
            dto.setColor(g.getColor());
            dto.setSortOrder(g.getSortOrder());
            dto.setAnimeCount(groupRepository.countAnimeByGroupId(g.getId()));
            return dto;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @Operation(summary = "创建分组")
    @PostMapping("/api/groups")
    public ResponseEntity<ApiResponse<AnimeGroupDTO>> createGroup(@RequestBody Map<String, String> body) {
        AnimeGroup group = new AnimeGroup();
        group.setName(body.get("name"));
        group.setDescription(body.getOrDefault("description", ""));
        group.setColor(body.getOrDefault("color", "#4a6ad0"));
        group.setSortOrder(0);
        group = groupRepository.save(group);
        AnimeGroupDTO dto = new AnimeGroupDTO();
        dto.setId(group.getId());
        dto.setName(group.getName());
        dto.setDescription(group.getDescription());
        dto.setColor(group.getColor());
        dto.setAnimeCount(0);
        return ResponseEntity.ok(ApiResponse.success("创建成功", dto));
    }

    @Operation(summary = "删除分组")
    @DeleteMapping("/api/groups/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteGroup(@PathVariable Long id) {
        groupRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }

    @Operation(summary = "获取分组中的番剧")
    @GetMapping("/api/groups/{id}/anime")
    public ResponseEntity<ApiResponse<List<AnimeVO>>> getGroupAnime(@PathVariable Long id) {
        List<Long> animeIds = groupRepository.findAnimeIdsByGroupId(id);
        List<AnimeVO> all = animeService.findAll();
        List<AnimeVO> filtered = all.stream()
                .filter(a -> animeIds.contains(a.getId()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(filtered));
    }

    @Operation(summary = "添加番剧到分组")
    @PostMapping("/api/groups/{groupId}/anime/{animeId}")
    public ResponseEntity<ApiResponse<Void>> addAnimeToGroup(@PathVariable Long groupId, @PathVariable Long animeId) {
        try {
            groupRepository.addAnimeToGroup(groupId, animeId);
            return ResponseEntity.ok(ApiResponse.success("已添加", null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "可能已在分组中"));
        }
    }

    @Operation(summary = "从分组移除番剧")
    @DeleteMapping("/api/groups/{groupId}/anime/{animeId}")
    public ResponseEntity<ApiResponse<Void>> removeAnimeFromGroup(@PathVariable Long groupId, @PathVariable Long animeId) {
        groupRepository.removeAnimeFromGroup(groupId, animeId);
        return ResponseEntity.ok(ApiResponse.success("已移除", null));
    }

    @Operation(summary = "获取番剧所属分组")
    @GetMapping("/api/anime/{animeId}/groups")
    public ResponseEntity<ApiResponse<List<Long>>> getAnimeGroups(@PathVariable Long animeId) {
        return ResponseEntity.ok(ApiResponse.success(groupRepository.findGroupIdsByAnimeId(animeId)));
    }
}
