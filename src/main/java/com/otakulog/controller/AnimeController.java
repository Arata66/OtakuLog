package com.otakulog.controller;

import com.otakulog.entity.Anime;
import com.otakulog.repository.AnimeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;

@Controller
public class AnimeController {

    @Autowired
    private AnimeRepository animeRepository;

    @GetMapping("/")
    public String index(Model model) {
        List<Anime> animeList = animeRepository.findAll();
        model.addAttribute("animeList", animeList);
        return "anime";
    }

    @PostMapping("/api/anime/{id}/next-episode")
    @ResponseBody
    public String nextEpisode(@PathVariable Long id) {
        Optional<Anime> optionalAnime = animeRepository.findById(id);
        
        if (optionalAnime.isPresent()) {
            Anime anime = optionalAnime.get();
            
            // 边界检查：不能超过总集数
            if (anime.getCurrentEpisode() < anime.getTotalEpisodes()) {
                anime.setCurrentEpisode(anime.getCurrentEpisode() + 1);
                
                // 自动更新状态：如果看完了就标记为finished
                if (anime.getCurrentEpisode().equals(anime.getTotalEpisodes())) {
                    anime.setStatus("finished");
                } else {
                    anime.setStatus("watching");
                }
                
                animeRepository.save(anime);
                return "success";
            }
            
            return "reached_max";  // 已经是最后一集了
        }
        
        return "failed";
    }

    @PostMapping("/api/anime/{id}/prev-episode")
    @ResponseBody
    public String prevEpisode(@PathVariable Long id) {
        Optional<Anime> optionalAnime = animeRepository.findById(id);
        
        if (optionalAnime.isPresent()) {
            Anime anime = optionalAnime.get();
            
            // 边界检查：不能小于1
            if (anime.getCurrentEpisode() > 1) {
                anime.setCurrentEpisode(anime.getCurrentEpisode() - 1);
                
                // 自动更新状态：回到上一集就改回watching
                anime.setStatus("watching");
                
                animeRepository.save(anime);
                return "success";
            }
            
            return "reached_min";  // 已经是第1集了
        }
        
        return "failed";
    }

    @PostMapping("/api/anime/add")
    @ResponseBody
    public String addAnime(
            @RequestParam String name,
            @RequestParam Integer totalEpisodes,
            @RequestParam String season,
            @RequestParam Double score,
            @RequestParam(required = false) String remark) {
        
        try {
            // 创建新的Anime对象
            Anime newAnime = new Anime();
            newAnime.setName(name);
            newAnime.setTotalEpisodes(totalEpisodes);
            newAnime.setSeason(season);
            newAnime.setScore(score);
            newAnime.setRemark(remark != null ? remark : "");
            
            // 设置默认值：从第1集开始，状态为watching
            newAnime.setCurrentEpisode(1);
            newAnime.setStatus("watching");
            
            // 保存到数据库
            animeRepository.save(newAnime);
            return "success";
        } catch (Exception e) {
            e.printStackTrace();
            return "failed";
        }
    }

    @PostMapping("/api/anime/{id}/update")
    @ResponseBody
    public String updateAnime(
            @PathVariable Long id,
            @RequestParam String name,
            @RequestParam String season,
            @RequestParam Double score,
            @RequestParam(required = false) String remark) {
        
        try {
            Optional<Anime> optionalAnime = animeRepository.findById(id);
            
            if (optionalAnime.isPresent()) {
                Anime anime = optionalAnime.get();
                anime.setName(name);
                anime.setSeason(season);
                anime.setScore(score);
                anime.setRemark(remark != null ? remark : "");
                
                animeRepository.save(anime);
                return "success";
            }
            
            return "not_found";
        } catch (Exception e) {
            e.printStackTrace();
            return "failed";
        }
    }

    @DeleteMapping("/api/anime/{id}")
    @ResponseBody
    public String deleteAnime(@PathVariable Long id) {
        try {
            Optional<Anime> optionalAnime = animeRepository.findById(id);
            
            if (optionalAnime.isPresent()) {
                animeRepository.deleteById(id);
                return "success";
            }
            
            return "not_found";
        } catch (Exception e) {
            e.printStackTrace();
            return "failed";
        }
    }

    @GetMapping("/api/anime/search")
    @ResponseBody
    public List<Anime> searchAnime(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "id-desc") String sortBy) {
        
        try {
            List<Anime> results;
            
            // 如果没有提供任何查询条件，返回全部
            if ((name == null || name.trim().isEmpty()) && 
                (status == null || status.trim().isEmpty())) {
                results = animeRepository.findAll();
            }
            // 两个条件都提供
            else if (name != null && !name.trim().isEmpty() && 
                status != null && !status.trim().isEmpty()) {
                results = animeRepository.findByNameContainingAndStatus(name, status);
            }
            // 只提供名称
            else if (name != null && !name.trim().isEmpty()) {
                results = animeRepository.findByNameContaining(name);
            }
            // 只提供状态
            else if (status != null && !status.trim().isEmpty()) {
                results = animeRepository.findByStatus(status);
            }
            else {
                results = animeRepository.findAll();
            }
            
            // 应用排序
            if ("score-desc".equals(sortBy)) {
                results.sort((a, b) -> {
                    if (a.getScore() == null) return 1;
                    if (b.getScore() == null) return -1;
                    return b.getScore().compareTo(a.getScore());
                });
            } else if ("score-asc".equals(sortBy)) {
                results.sort((a, b) -> {
                    if (a.getScore() == null) return -1;
                    if (b.getScore() == null) return 1;
                    return a.getScore().compareTo(b.getScore());
                });
            } else if ("progress-desc".equals(sortBy)) {
                results.sort((a, b) -> {
                    double progressA = (double) a.getCurrentEpisode() / a.getTotalEpisodes();
                    double progressB = (double) b.getCurrentEpisode() / b.getTotalEpisodes();
                    return Double.compare(progressB, progressA);
                });
            } else if ("progress-asc".equals(sortBy)) {
                results.sort((a, b) -> {
                    double progressA = (double) a.getCurrentEpisode() / a.getTotalEpisodes();
                    double progressB = (double) b.getCurrentEpisode() / b.getTotalEpisodes();
                    return Double.compare(progressA, progressB);
                });
            } else if ("name-asc".equals(sortBy)) {
                results.sort((a, b) -> a.getName().compareTo(b.getName()));
            } else {
                // id-desc 默认排序（最新添加的在前）
                results.sort((a, b) -> b.getId().compareTo(a.getId()));
            }
            
            return results;
        } catch (Exception e) {
            e.printStackTrace();
            return animeRepository.findAll();
        }
    }

    @GetMapping("/api/anime/stats")
    @ResponseBody
    public Map<String, Object> getStats() {
        try {
            List<Anime> allAnime = animeRepository.findAll();
            
            Map<String, Object> stats = new HashMap<>();
            
            // 总数
            int total = allAnime.size();
            
            // 各状态计数
            long watching = allAnime.stream().filter(a -> "watching".equals(a.getStatus())).count();
            long finished = allAnime.stream().filter(a -> "finished".equals(a.getStatus())).count();
            long planning = allAnime.stream().filter(a -> "planning".equals(a.getStatus())).count();
            long dropped = allAnime.stream().filter(a -> "dropped".equals(a.getStatus())).count();
            
            stats.put("total", total);
            stats.put("watching", watching);
            stats.put("finished", finished);
            stats.put("planning", planning);
            stats.put("dropped", dropped);
            
            return stats;
        } catch (Exception e) {
            e.printStackTrace();
            return new HashMap<>();
        }
    }

    @PostMapping("/api/anime/{id}/status")
    @ResponseBody
    public String updateStatus(
            @PathVariable Long id,
            @RequestParam String status) {
        
        try {
            Optional<Anime> optionalAnime = animeRepository.findById(id);
            
            if (optionalAnime.isPresent()) {
                Anime anime = optionalAnime.get();
                anime.setStatus(status);
                animeRepository.save(anime);
                return "success";
            }
            
            return "not_found";
        } catch (Exception e) {
            e.printStackTrace();
            return "failed";
        }
    }

    @GetMapping("/api/anime/stats/detailed")
    @ResponseBody
    public Map<String, Object> getDetailedStats() {
        try {
            List<Anime> allAnime = animeRepository.findAll();
            
            Map<String, Object> stats = new HashMap<>();
            
            // 基础统计
            int total = allAnime.size();
            long watching = allAnime.stream().filter(a -> "watching".equals(a.getStatus())).count();
            long finished = allAnime.stream().filter(a -> "finished".equals(a.getStatus())).count();
            long planning = allAnime.stream().filter(a -> "planning".equals(a.getStatus())).count();
            long dropped = allAnime.stream().filter(a -> "dropped".equals(a.getStatus())).count();
            
            stats.put("total", total);
            stats.put("watching", watching);
            stats.put("finished", finished);
            stats.put("planning", planning);
            stats.put("dropped", dropped);
            
            // 进度统计
            long totalEpisodes = allAnime.stream().mapToLong(Anime::getTotalEpisodes).sum();
            long watchedEpisodes = allAnime.stream().mapToLong(Anime::getCurrentEpisode).sum();
            double progressPercentage = totalEpisodes > 0 ? (watchedEpisodes * 100.0 / totalEpisodes) : 0;
            
            stats.put("totalEpisodes", totalEpisodes);
            stats.put("watchedEpisodes", watchedEpisodes);
            stats.put("progressPercentage", Math.round(progressPercentage * 10.0) / 10.0); // 保留一位小数
            
            // 平均评分
            double avgScore = allAnime.stream()
                    .filter(a -> a.getScore() != null && a.getScore() > 0)
                    .mapToDouble(Anime::getScore)
                    .average()
                    .orElse(0.0);
            
            stats.put("averageScore", Math.round(avgScore * 10.0) / 10.0);
            
            // 评分统计
            long highScore = allAnime.stream().filter(a -> a.getScore() != null && a.getScore() >= 8.0).count();
            long mediumScore = allAnime.stream().filter(a -> a.getScore() != null && a.getScore() >= 6.0 && a.getScore() < 8.0).count();
            long lowScore = allAnime.stream().filter(a -> a.getScore() != null && a.getScore() > 0 && a.getScore() < 6.0).count();
            
            stats.put("highScore", highScore);      // 评分 >= 8.0
            stats.put("mediumScore", mediumScore);  // 评分 6.0-8.0
            stats.put("lowScore", lowScore);        // 评分 < 6.0
            
            return stats;
        } catch (Exception e) {
            e.printStackTrace();
            return new HashMap<>();
        }
    }

}
