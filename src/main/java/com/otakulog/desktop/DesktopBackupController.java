package com.otakulog.desktop;

import com.otakulog.common.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("desktop")
public class DesktopBackupController {
    private final DesktopBackupManager manager;
    public DesktopBackupController(DesktopBackupManager manager) { this.manager = manager; }
    @GetMapping("/api/desktop/backups/status")
    public ApiResponse<DesktopBackupManager.Status> status() { return ApiResponse.success(manager.status()); }
}
