package com.otakulog.enums;

public enum AnimeStatus {
    WATCHING("追中"),
    FINISHED("已完成"),
    PLANNING("计划"),
    DROPPED("放弃");

    private final String displayName;

    AnimeStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
