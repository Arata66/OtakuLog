package com.otakulog.dto;

import java.util.List;

public class BatchRequest {
    private List<Long> ids;
    private String status;

    public List<Long> getIds() { return ids; }
    public void setIds(List<Long> ids) { this.ids = ids; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
