package com.otakulog.dto;

public class BangumiEpisode {
    private int id;
    private String name;
    private String nameCn;
    private String airdate;
    private int sort;
    private Integer duration;
    private String desc;

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNameCn() { return nameCn; }
    public void setNameCn(String nameCn) { this.nameCn = nameCn; }
    public String getAirdate() { return airdate; }
    public void setAirdate(String airdate) { this.airdate = airdate; }
    public int getSort() { return sort; }
    public void setSort(int sort) { this.sort = sort; }
    public Integer getDuration() { return duration; }
    public void setDuration(Integer duration) { this.duration = duration; }
    public String getDesc() { return desc; }
    public void setDesc(String desc) { this.desc = desc; }
}
