package com.otakulog.desktop;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
@Profile("desktop")
public class DesktopDataDirectoryConfiguration {

    @Bean
    public FlywayConfigurationCustomizer desktopFlywayConfigurationCustomizer() {
        // 旧程序必须拒绝尚不认识的迁移版本，避免写坏新版资料。
        return configuration -> configuration.ignoreMigrationPatterns(new String[0]);
    }

    @Bean(destroyMethod = "close")
    public DesktopUpgradeGuard desktopUpgradeGuard(@Value("${app.desktop.data-dir:}") String directory,
            @Value("${app.desktop.backup-dir:}") String backupDirectory) {
        Path dataDirectory = validateDirectory(directory);
        Path backup = backupDirectory.isBlank() ? dataDirectory.resolve("backups") : Path.of(backupDirectory);
        if (!backup.isAbsolute()) throw new IllegalArgumentException("桌面备份目录必须为绝对路径");
        if (backup.toString().contains(";")) throw new IllegalArgumentException("桌面备份目录不能包含分号");
        return new DesktopUpgradeGuard(dataDirectory, backup.normalize());
    }

    @Bean
    public FlywayMigrationStrategy desktopMigrationStrategy(DesktopUpgradeGuard guard) {
        return guard::migrate;
    }

    @Bean
    @FlywayDataSource
    public DriverManagerDataSource desktopMigrationDataSource(DesktopUpgradeGuard guard) {
        // Flyway 在定制器之前就会读取连接，必须从首次配置起使用可完全关闭的非池连接。
        return new DriverManagerDataSource(guard.jdbcUrl(), "sa", "");
    }

    @Bean(destroyMethod = "close")
    @Primary
    public HikariDataSource desktopDataSource(DesktopUpgradeGuard guard) {
        var dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        // 从已验证目录派生连接，防止开发者的数据库环境变量切换桌面资料库。
        // 确认保存后立即结束主进程也需保留记录，提交不能继续等待默认延迟落盘。
        dataSource.setJdbcUrl(guard.jdbcUrl());
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private Path validateDirectory(String directory) {
        if (directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("必须通过 app.desktop.data-dir 传入独立的桌面资料目录绝对路径");
        }
        // 分号会被 H2 解释为连接选项，不能作为资料目录的一部分。
        if (directory.contains(";")) throw new IllegalArgumentException("桌面资料目录不能包含分号");
        try {
            Path path = Path.of(directory);
            if (!path.isAbsolute()) throw new IllegalArgumentException("桌面资料目录必须使用绝对路径");
            Files.createDirectories(path.normalize());
            Path realPath = path.toRealPath();
            if (realPath.toString().contains(";")) throw new IllegalArgumentException("桌面资料目录不能包含分号");
            if (!Files.isDirectory(realPath) || !Files.isWritable(realPath)) {
                throw new IllegalArgumentException("桌面资料目录必须是可写目录");
            }
            return realPath;
        } catch (InvalidPathException | IOException failure) {
            throw new IllegalArgumentException("无法使用桌面资料目录，请检查路径和写入权限", failure);
        }
    }
}
