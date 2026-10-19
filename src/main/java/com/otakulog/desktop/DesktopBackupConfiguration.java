package com.otakulog.desktop;

import com.otakulog.service.BackupService;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.context.annotation.Role;
import org.springframework.beans.factory.config.BeanDefinition;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Profile("desktop")
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class DesktopBackupConfiguration {
    @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public DesktopSnapshotGate desktopSnapshotGate() { return new DesktopSnapshotGate(); }
    @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public Advisor desktopSnapshotExportAdvisor(DesktopSnapshotGate gate) {
        var pointcut = new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method, Class<?> targetClass) {
                return BackupService.class.isAssignableFrom(targetClass) && method.getName().equals("exportJson");
            }
        };
        var advisor = new DefaultPointcutAdvisor(pointcut, (MethodInterceptor) invocation -> {
            // 排他屏障先于导出事务，第一张表读取前所有在途写入必须已经结束。
            try (var snapshot = gate.snapshot()) { return invocation.proceed(); }
        });
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE + 50); return advisor;
    }
    @Bean(destroyMethod = "close")
    public DesktopBackupManager desktopBackupManager(@Value("${app.desktop.data-dir}") String directory,
            @Value("${app.desktop.backup-dir:}") String backupDirectory, BackupService backup, DesktopSnapshotGate gate) {
        Path root = backupDirectory.isBlank() ? Path.of(directory).resolve("backups") : Path.of(backupDirectory);
        if (!root.isAbsolute()) throw new IllegalArgumentException("桌面备份目录必须为绝对路径");
        var manager = new DesktopBackupManager(root, Clock.systemDefaultZone(), backup::exportJson, backup::validateDocument);
        gate.onCommitted(manager::committed); return manager;
    }
}
