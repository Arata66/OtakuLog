package com.otakulog.dto;

public class BangumiResult {
    private int id;
    private String name;
    private String nameCn;
    private String image;
    private String date;
    private Integer eps;
    private Double score;
    private Integer rank;

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNameCn() { return nameCn; }
    public void setNameCn(String nameCn) { this.nameCn = nameCn; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public Integer getEps() { return eps; }
    public void setEps(Integer eps) { this.eps = eps; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }
}
