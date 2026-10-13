package com.otakulog.controller;

import com.fasterxml.jackson.databind.*;
import com.otakulog.common.ApiResponse;
import com.otakulog.dto.RecommendationBasisDTO.*;
import com.otakulog.service.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/recommendations/bases")
public class RecommendationBasisController {
    private final RecommendationBasisService service;private final ObjectMapper mapper;
    public RecommendationBasisController(RecommendationBasisService service,ObjectMapper mapper){this.service=service;this.mapper=mapper;}
    @GetMapping public ApiResponse<Page> get(){return ApiResponse.success(service.get());}
    @PostMapping("/preview") public ApiResponse<Preview> preview(@RequestBody JsonNode input){return ApiResponse.success(service.preview(decode(input)));}
    @PostMapping public ApiResponse<Entry> save(@RequestBody JsonNode input){return ApiResponse.success(service.save(decode(input)));}
    @DeleteMapping("/{key}") public ApiResponse<String> disable(@PathVariable String key,@RequestParam long expectedVersion){service.disable(key,expectedVersion);return ApiResponse.success("关联已停用");}
    private Write decode(JsonNode node){RecommendationJson.strict(node,Write.class,false);try{return mapper.treeToValue(node,Write.class);}catch(Exception e){throw new IllegalArgumentException("关联字段类型无效",e);}}
}
