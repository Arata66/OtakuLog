package com.otakulog.desktop;

import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class DesktopSnapshotGate implements TransactionExecutionListener, AopInfrastructureBean {
    private final ReentrantReadWriteLock barrier = new ReentrantReadWriteLock(true);
    private final ConcurrentHashMap<TransactionExecution, Boolean> writers = new ConcurrentHashMap<>();
    private volatile Runnable committed = () -> {};

    public void onCommitted(Runnable listener) { committed = listener; }
    @Override public void beforeBegin(TransactionExecution transaction) {
        if (transaction.isReadOnly()) return;
        barrier.readLock().lock();
        writers.put(transaction, true);
    }
    @Override public void afterBegin(TransactionExecution transaction, Throwable failure) { if (failure != null) release(transaction); }
    @Override public void afterCommit(TransactionExecution transaction, Throwable failure) {
        boolean changed = release(transaction);
        // 业务已经提交，备份排队失败不能让页面把本次保存误报成失败。
        if (changed && failure == null) try { committed.run(); } catch (RuntimeException ignored) { }
    }
    @Override public void afterRollback(TransactionExecution transaction, Throwable failure) { release(transaction); }
    private boolean release(TransactionExecution transaction) {
        if (writers.remove(transaction) == null) return false;
        barrier.readLock().unlock(); return true;
    }
    public Snapshot snapshot() {
        if (barrier.getReadHoldCount() > 0) throw new IllegalStateException("请在写入事务外导出完整备份");
        barrier.writeLock().lock(); return new Snapshot();
    }
    public final class Snapshot implements AutoCloseable {
        private boolean closed;
        private Snapshot() {}
        @Override public void close() { if (!closed) { closed = true; barrier.writeLock().unlock(); } }
    }
}
