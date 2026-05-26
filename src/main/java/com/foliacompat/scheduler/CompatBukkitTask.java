package com.foliacompat.scheduler;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * BukkitTask 兼容包装，内部持有 Folia ScheduledTask。
 *
 * 线程安全设计：
 * - foliaTask 使用 AtomicReference，解决 cancel() 在 setFoliaTask() 之前调用的竞态
 * - cancelled 使用 volatile，确保跨线程可见性
 */
public class CompatBukkitTask implements BukkitTask {

    private final Plugin owner;
    private final int taskId;
    private final boolean sync;
    private final long period; // 0=非重复，>0=重复周期(tick)
    private volatile boolean cancelled = false;
    private final AtomicReference<ScheduledTask> foliaTask = new AtomicReference<>();

    // 保存原始任务引用，用于自调度重复任务
    private final Runnable work;
    private final Consumer<BukkitTask> workConsumer;

    public CompatBukkitTask(Plugin owner, int taskId, boolean sync) {
        this(owner, taskId, sync, 0, null, (Consumer<BukkitTask>) null);
    }

    public CompatBukkitTask(Plugin owner, int taskId, boolean sync, long period, Runnable work) {
        this(owner, taskId, sync, period, work, (Consumer<BukkitTask>) null);
    }

    public CompatBukkitTask(Plugin owner, int taskId, boolean sync, long period, Runnable work, Consumer<BukkitTask> workConsumer) {
        this.owner = owner;
        this.taskId = taskId;
        this.sync = sync;
        this.period = period;
        this.work = work;
        this.workConsumer = workConsumer;
    }

    /**
     * 设置 Folia ScheduledTask 引用。
     * 如果任务已被取消，立即 cancel 刚设置的 foliaTask。
     */
    public void setFoliaTask(ScheduledTask task) {
        if (!foliaTask.compareAndSet(null, task)) {
            // 已有引用（不应该发生），直接 cancel 新的
            task.cancel();
            return;
        }
        // 设置成功后检查是否已取消
        if (cancelled) {
            ScheduledTask current = foliaTask.getAndSet(null);
            if (current != null) {
                current.cancel();
            }
        }
    }

    @Override
    public int getTaskId() {
        return taskId;
    }

    @Override
    public Plugin getOwner() {
        return owner;
    }

    @Override
    public boolean isSync() {
        return sync;
    }

    @Override
    public boolean isCancelled() {
        if (cancelled) return true;
        ScheduledTask ft = foliaTask.get();
        return ft != null && ft.isCancelled();
    }

    @Override
    public void cancel() {
        cancelled = true;
        ScheduledTask ft = foliaTask.getAndSet(null);
        if (ft != null) {
            ft.cancel();
        }
    }

    public long getPeriod() {
        return period;
    }

    public boolean isRepeating() {
        return period > 0;
    }

    public Runnable getWork() {
        return work;
    }

    public Consumer<BukkitTask> getWorkConsumer() {
        return workConsumer;
    }

    /**
     * 当前持有的 Folia ScheduledTask 引用（可能为 null）。
     */
    public ScheduledTask getFoliaTask() {
        return foliaTask.get();
    }
}
