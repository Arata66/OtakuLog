package com.otakulog.desktop;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.Consumer;

public final class DesktopBackupManager implements AutoCloseable {
    private final Path root, daily;
    private final Clock clock;
    private final Supplier<String> export;
    private final Consumer<String> validate;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "otakulog-daily-backup"); thread.setDaemon(true); return thread;
    });
    private final AtomicInteger pending = new AtomicInteger();
    private volatile LocalDate lastSuccessfulDay;
    private volatile String warning;
    private volatile boolean initialized, closed;
    private byte[] baseline;

    public DesktopBackupManager(Path root, Clock clock, Supplier<String> export, Consumer<String> validate) {
        this.root = root.toAbsolutePath().normalize(); this.daily = this.root.resolve("daily");
        this.clock = clock; this.export = export; this.validate = validate;
    }
    @EventListener(ApplicationStartedEvent.class)
    public void initialize() {
        if (initialized) return;
        try {
            baseline = digest(export.get());
            lastSuccessfulDay = dailyFiles().stream().map(this::date).max(LocalDate::compareTo).orElse(null);
        } catch (Exception failure) {
            warning = "自动备份初始化未完成，原资料保留。请检查备份目录，下次修改会重试。";
        } finally { initialized = true; }
    }
    public void committed() {
        if (!initialized || closed) return;
        pending.incrementAndGet();
        try { worker.execute(() -> { try { check(); } finally { pending.decrementAndGet(); } }); }
        catch (RejectedExecutionException ignored) { pending.decrementAndGet(); }
    }
    private void check() {
        try {
            String snapshot = export.get(); byte[] digest = digest(snapshot);
            if (baseline != null && MessageDigest.isEqual(baseline, digest)) return;
            LocalDate today = LocalDate.now(clock); Path destination = daily.resolve(today + ".json");
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                if (!validFile(destination)) throw new IOException("原每日文件不可校验");
                lastSuccessfulDay = today; prune(); baseline = digest; warning = null; return;
            }
            if (Files.isSymbolicLink(root) || Files.isSymbolicLink(daily)) throw new IOException("备份目录不是普通目录");
            Files.createDirectories(daily);
            Path temporary = daily.resolve(".otakulog-daily-" + UUID.randomUUID() + ".tmp");
            try {
                byte[] bytes = snapshot.getBytes(StandardCharsets.UTF_8);
                try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                if (!Arrays.equals(bytes, Files.readAllBytes(temporary))) throw new IOException("备份写入校验失败");
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
            // 新备份完整落盘后才轮转，只处理此目录中可校验的每日文件。
            lastSuccessfulDay = today; prune(); baseline = digest; warning = null;
        } catch (Exception failure) {
            warning = "自动备份未完成，已保存的记录和已有备份保留。请检查备份目录、权限及磁盘空间；下次修改会重试。";
        }
    }
    private void prune() throws IOException {
        var files = dailyFiles();
        for (int index = 0; index < files.size() - 7; index++) Files.delete(files.get(index));
    }
    private byte[] digest(String snapshot) throws Exception {
        validate.accept(snapshot);
        var root = json.readTree(snapshot);
        if (!(root instanceof ObjectNode document) || !"otakulog-backup".equals(document.path("format").asText())
                || !document.path("version").isIntegralNumber() || document.path("version").intValue() != 6) throw new IOException("完整备份格式无效");
        document.remove("exportedAt");
        return MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(document));
    }
    private boolean validFile(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        try { digest(Files.readString(file)); return true; } catch (Exception ignored) { return false; }
    }
    private LocalDate date(Path path) { return LocalDate.parse(path.getFileName().toString().substring(0, 10)); }
    private List<Path> dailyFiles() throws IOException {
        if (!Files.exists(daily)) return List.of();
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(daily) || !Files.isDirectory(daily)) throw new IOException("备份目录无法读取");
        try (var files = Files.list(daily)) {
            return files.filter(file -> file.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.json"))
                    .filter(file -> { try { date(file); return validFile(file); } catch (RuntimeException ignored) { return false; } })
                    .sorted(Comparator.comparing(this::date)).toList();
        }
    }
    public Status status() { return new Status(lastSuccessfulDay, pending.get() > 0, warning, root.toString()); }
    public record Status(LocalDate lastSuccessfulDay, boolean pending, String warning, String directory) {}
    public void flush() throws InterruptedException, ExecutionException {
        if (!closed) worker.submit(() -> {}).get();
    }
    @Override public void close() throws InterruptedException {
        closed = true; worker.shutdown();
        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) { }
    }
}
