package com.otakulog.controller;

import com.otakulog.entity.Anime;
import com.otakulog.repository.AnimeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;
import java.util.Optional;

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

}
