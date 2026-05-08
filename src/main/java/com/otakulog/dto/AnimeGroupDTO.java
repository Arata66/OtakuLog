package com.otakulog.dto;

public class AnimeGroupDTO {
    private Long id;
    private String name;
    private String description;
    private String color;
    private Integer sortOrder;
    private long animeCount;

    public AnimeGroupDTO() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public long getAnimeCount() { return animeCount; }
    public void setAnimeCount(long animeCount) { this.animeCount = animeCount; }
}
