package com.otakulog.entity;

import jakarta.persistence.*;

@Entity @Table(name = "import_lock")
public class ImportLock {
    @Id private Long id;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
}
