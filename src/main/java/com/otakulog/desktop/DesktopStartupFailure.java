package com.otakulog.desktop;

import java.nio.file.Path;

public final class DesktopStartupFailure extends IllegalStateException {
    public enum Code { ENGINE_MISMATCH, BACKUP_FAILED, DATABASE_BUSY, SCHEMA_NEWER, UPGRADE_BLOCKED }

    DesktopStartupFailure(Code code, String message, Path dataDirectory, Path backupDirectory, Throwable cause) {
        super(message + "。资料目录：" + dataDirectory + "；升级备份目录：" + backupDirectory, cause);
        // 壳只接受固定错误码，路径与可见说明由本次可信启动参数构造。
        System.out.println("OTAKULOG_DESKTOP_FAILURE {\"code\":\"" + code.name() + "\"}");
        System.out.flush();
    }
}
