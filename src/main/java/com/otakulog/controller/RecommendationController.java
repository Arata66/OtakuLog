package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.dto.RecommendationDTO.*;
import com.otakulog.service.RecommendationService;
import com.otakulog.service.RecommendationState;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import com.fasterxml.jackson.databind.*;
import com.otakulog.service.RecommendationJson;

@RestController @RequestMapping("/api/recommendations")
public class RecommendationController {
    private final RecommendationService service;private final RecommendationState state;private final ObjectMapper mapper;
    public RecommendationController(RecommendationService service,RecommendationState state,ObjectMapper mapper){this.service=service;this.state=state;this.mapper=mapper;}
    @GetMapping public ApiResponse<Result> recommend(@RequestParam(defaultValue="false")boolean includeRecent,Principal principal){return ApiResponse.success(service.recommend(principal.getName(),includeRecent));}
    @GetMapping("/settings") public ApiResponse<Settings> settings(){return ApiResponse.success(state.settings());}
    @PutMapping("/settings") public ApiResponse<Settings> settings(@RequestBody JsonNode settings){return ApiResponse.success(state.save(decode(settings,Settings.class,true)));}
    @GetMapping("/feedback") public ApiResponse<FeedbackPage> feedback(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return ApiResponse.success(state.feedbackPage(page,size));}
    @PostMapping("/feedback") public ApiResponse<FeedbackEntry> feedback(@RequestBody JsonNode request,Principal principal){return ApiResponse.success(service.feedback(principal.getName(),decode(request,FeedbackRequest.class,false)));}
    @DeleteMapping("/feedback/{key}") public ApiResponse<String> remove(@PathVariable String key,@RequestParam long expectedVersion){state.remove(key,expectedVersion);return ApiResponse.success("反馈已撤销");}
    @PostMapping("/shown") public ApiResponse<String> shown(@RequestBody JsonNode request,Principal principal){service.shown(principal.getName(),decode(request,ShownRequest.class,false));return ApiResponse.success("近期展示已保存");}
    private <T>T decode(JsonNode node,Class<T> type,boolean defaultKey){RecommendationJson.strict(node,type,defaultKey);try{return mapper.treeToValue(node,type);}catch(Exception e){throw new IllegalArgumentException("推荐字段或类型无效",e);}}
}
