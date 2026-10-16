package com.otakulog.desktop;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
@Profile("desktop")
public class DesktopDataDirectoryConfiguration {

    @Bean(destroyMethod = "close")
    public HikariDataSource desktopDataSource(@Value("${app.desktop.data-dir:}") String directory) {
        Path dataDirectory = validateDirectory(directory);
        var dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        // 从已验证目录派生连接，防止开发者的数据库环境变量切换桌面资料库。
        dataSource.setJdbcUrl("jdbc:h2:file:" + dataDirectory.resolve("otakulog").toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE");
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
