package com.otakulog.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "ai_quota")
public class AiQuota {
    @Id private Long id;
    @Column(name = "quota_date", nullable = false) private LocalDate quotaDate;
    @Column(name = "reserved_calls", nullable = false) private int reservedCalls;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public LocalDate getQuotaDate() { return quotaDate; }
    public void setQuotaDate(LocalDate value) { quotaDate = value; }
    public int getReservedCalls() { return reservedCalls; }
    public void setReservedCalls(int value) { reservedCalls = value; }
}
