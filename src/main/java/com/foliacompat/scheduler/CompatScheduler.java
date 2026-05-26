package com.foliacompat.scheduler;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitWorker;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * BukkitScheduler 兼容实现。
 * 将旧的 BukkitScheduler 调用重定向到 Folia 的多种调度器。
 *
 * 路由策略：
 * - runTask → GlobalRegionScheduler (最安全的默认选择)
 * - runTaskAsynchronously → AsyncScheduler
 * - runTaskLater → GlobalRegionScheduler (延迟)
 * - runTaskTimer → GlobalRegionScheduler (自调度重复)
 * - runTaskLaterAsynchronously / runTaskTimerAsynchronously → AsyncScheduler
 */
public class CompatScheduler implements BukkitScheduler {

    private static final Logger LOGGER = Logger.getLogger("FoliaCompat");
    private final AtomicInteger taskCounter = new AtomicInteger(0);
    private final TaskRegistry registry = new TaskRegistry();
    private volatile boolean shutdown = false;

    // ==================== runTask (sync) ====================

    @Override
    public BukkitTask runTask(Plugin plugin, Runnable task) {
        CompatBukkitTask compatTask = createTask(plugin, true, 0, task);
        SchedulerRouter.runGlobalTask(plugin, foliaTask -> {
            compatTask.setFoliaTask(foliaTask);
            task.run();
        });
        return compatTask;
    }

    @Override
    public void runTask(Plugin plugin, Consumer<? super BukkitTask> task) {
        CompatBukkitTask compatTask = createTask(plugin, true, 0, null, task);
        SchedulerRouter.runGlobalTask(plugin, foliaTask -> {
            compatTask.setFoliaTask(foliaTask);
            task.accept(compatTask);
        });
    }

    @Override
    public BukkitTask runTask(Plugin plugin, BukkitRunnable task) {
        return runTask(plugin, (Runnable) task);
    }

    // ==================== runTaskAsynchronously ====================

    @Override
    public BukkitTask runTaskAsynchronously(Plugin plugin, Runnable task) {
        CompatBukkitTask compatTask = createTask(plugin, false, 0, task);
        SchedulerRouter.runAsyncTask(plugin, foliaTask -> {
            compatTask.setFoliaTask(foliaTask);
            task.run();
        });
        return compatTask;
    }

    @Override
    public void runTaskAsynchronously(Plugin plugin, Consumer<? super BukkitTask> task) {
        CompatBukkitTask compatTask = createTask(plugin, false, 0, null, task);
        SchedulerRouter.runAsyncTask(plugin, foliaTask -> {
            compatTask.setFoliaTask(foliaTask);
            task.accept(compatTask);
        });
    }

    @Override
    public BukkitTask runTaskAsynchronously(Plugin plugin, BukkitRunnable task) {
        return runTaskAsynchronously(plugin, (Runnable) task);
    }

    // ==================== runTaskLater (sync) ====================

    @Override
    public BukkitTask runTaskLater(Plugin plugin, Runnable task, long delay) {
        CompatBukkitTask compatTask = createTask(plugin, true, 0, task);
        SchedulerRouter.runGlobalTaskDelayed(plugin, foliaTask -> {
            compatTask.setFoliaTask(foliaTask);
            task.run();
        }, delay);
        return compatTask;
    }

    @Override
    public void runTaskLater(Plugin plugin, Consumer<? super BukkitTask> task, long delay) {
        CompatBukkitTask compatTask = createTask(plugin, true, 0, null, task);
        SchedulerRouter.runGlobalTaskDelayed(plugin, foliaTask -> {
            compatTask.setFoliaTask(foliaTask);
            task.accept(compatTask);
        }, delay);
    }

    @Override
    public BukkitTask runTaskLater(Plugin plugin, BukkitRunnable task, long delay) {
        return runTaskLater(plugin, (Runnable) task, delay);
    }

    // ==================== runTaskLaterAsynchronously ====================

    @Override
    public BukkitTask runTaskLaterAsynchronously(Plugin plugin, Runnable task, long delay) {
        CompatBukkitTask compatTask = createTask(plugin, false, 0, task);
        SchedulerRouter.runAsyncTaskDelayed(plugin, foliaTask -> {
            compatTask.setFoliaTask(foliaTask);
            task.run();
        }, delay);
        return compatTask;
    }

    @Override
    public void runTaskLaterAsynchronously(Plugin plugin, Consumer<? super BukkitTask> task, long delay) {
        CompatBukkitTask compatTask = createTask(plugin, false, 0, null, task);
        SchedulerRouter.runAsyncTaskDelayed(plugin, foliaTask -> {
            compatTask.setFoliaTask(foliaTask);
            task.accept(compatTask);
        }, delay);
    }

    @Override
    public BukkitTask runTaskLaterAsynchronously(Plugin plugin, BukkitRunnable task, long delay) {
        return runTaskLaterAsynchronously(plugin, (Runnable) task, delay);
    }

    // ==================== runTaskTimer (sync, self-rescheduling) ====================

    @Override
    public BukkitTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        CompatBukkitTask compatTask = createTask(plugin, true, period, task);
        if (delay <= 0) {
            SchedulerRouter.runGlobalTask(plugin, foliaTask -> {
                compatTask.setFoliaTask(foliaTask);
                if (!compatTask.isCancelled()) {
                    task.run();
                }
                scheduleNextSync(compatTask, period);
            });
        } else {
            SchedulerRouter.runGlobalTaskDelayed(plugin, foliaTask -> {
                compatTask.setFoliaTask(foliaTask);
                if (!compatTask.isCancelled()) {
                    task.run();
                }
                scheduleNextSync(compatTask, period);
            }, delay);
        }
        return compatTask;
    }

    @Override
    public void runTaskTimer(Plugin plugin, Consumer<? super BukkitTask> task, long delay, long period) {
        CompatBukkitTask compatTask = createTask(plugin, true, period, null, task);
        if (delay <= 0) {
            SchedulerRouter.runGlobalTask(plugin, foliaTask -> {
                compatTask.setFoliaTask(foliaTask);
                if (!compatTask.isCancelled()) {
                    task.accept(compatTask);
                }
                scheduleNextSyncConsumer(compatTask, task, period);
            });
        } else {
            SchedulerRouter.runGlobalTaskDelayed(plugin, foliaTask -> {
                compatTask.setFoliaTask(foliaTask);
                if (!compatTask.isCancelled()) {
                    task.accept(compatTask);
                }
                scheduleNextSyncConsumer(compatTask, task, period);
            }, delay);
        }
    }

    @Override
    public BukkitTask runTaskTimer(Plugin plugin, BukkitRunnable task, long delay, long period) {
        return runTaskTimer(plugin, (Runnable) task, delay, period);
    }

    // ==================== runTaskTimerAsynchronously ====================

    @Override
    public BukkitTask runTaskTimerAsynchronously(Plugin plugin, Runnable task, long delay, long period) {
        CompatBukkitTask compatTask = createTask(plugin, false, period, task);
        SchedulerRouter.runAsyncTaskRepeating(plugin, foliaTask -> {
            compatTask.setFoliaTask(foliaTask);
            if (!compatTask.isCancelled()) {
                task.run();
            } else {
                foliaTask.cancel();
            }
        }, delay, period);
        return compatTask;
    }

    @Override
    public void runTaskTimerAsynchronously(Plugin plugin, Consumer<? super BukkitTask> task, long delay, long period) {
        CompatBukkitTask compatTask = createTask(plugin, false, period, null, task);
        SchedulerRouter.runAsyncTaskRepeating(plugin, foliaTask -> {
            compatTask.setFoliaTask(foliaTask);
            if (!compatTask.isCancelled()) {
                task.accept(compatTask);
            } else {
                foliaTask.cancel();
            }
        }, delay, period);
    }

    @Override
    public BukkitTask runTaskTimerAsynchronously(Plugin plugin, BukkitRunnable task, long delay, long period) {
        return runTaskTimerAsynchronously(plugin, (Runnable) task, delay, period);
    }

    // ==================== 自调度重复任务核心 ====================

    /**
     * 自调度同步重复任务（Runnable 版本）。
     * Folia 的 GlobalRegionScheduler 没有 runAtFixedRate，
     * 因此通过 runDelayed 递归调度自身实现。
     */
    private void scheduleNextSync(CompatBukkitTask compatTask, long period) {
        if (compatTask.isCancelled() || shutdown) return;
        // Folia requires delay > 0 for runDelayed; ensure at least 1 tick period
        long safePeriod = Math.max(1, period);
        SchedulerRouter.runGlobalTaskDelayed(compatTask.getOwner(), foliaTask -> {
            compatTask.setFoliaTask(foliaTask);
            if (compatTask.isCancelled()) return;
            Runnable work = compatTask.getWork();
            if (work != null) {
                work.run();
            }
            scheduleNextSync(compatTask, safePeriod);
        }, safePeriod);
    }

    /**
     * 自调度同步重复任务（Consumer 版本）。
     */
    private void scheduleNextSyncConsumer(CompatBukkitTask compatTask, Consumer<? super BukkitTask> task, long period) {
        if (compatTask.isCancelled() || shutdown) return;
        long safePeriod = Math.max(1, period);
        SchedulerRouter.runGlobalTaskDelayed(compatTask.getOwner(), foliaTask -> {
            compatTask.setFoliaTask(foliaTask);
            if (compatTask.isCancelled()) return;
            task.accept(compatTask);
            scheduleNextSyncConsumer(compatTask, task, safePeriod);
        }, safePeriod);
    }

    // ==================== callSyncMethod ====================

    @Override
    public <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task) {
        java.util.concurrent.CompletableFuture<T> future = new java.util.concurrent.CompletableFuture<>();
        SchedulerRouter.runGlobalTask(plugin, foliaTask -> {
            try {
                future.complete(task.call());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    // ==================== 废弃的 schedule 方法 ====================

    @Override
    public int scheduleSyncDelayedTask(Plugin plugin, Runnable task, long delay) {
        return runTaskLater(plugin, task, delay).getTaskId();
    }

    @Override
    public int scheduleSyncDelayedTask(Plugin plugin, BukkitRunnable task, long delay) {
        return scheduleSyncDelayedTask(plugin, (Runnable) task, delay);
    }

    @Override
    public int scheduleSyncDelayedTask(Plugin plugin, Runnable task) {
        return runTask(plugin, task).getTaskId();
    }

    @Override
    public int scheduleSyncDelayedTask(Plugin plugin, BukkitRunnable task) {
        return scheduleSyncDelayedTask(plugin, (Runnable) task);
    }

    @Override
    public int scheduleSyncRepeatingTask(Plugin plugin, Runnable task, long delay, long period) {
        return runTaskTimer(plugin, task, delay, period).getTaskId();
    }

    @Override
    public int scheduleSyncRepeatingTask(Plugin plugin, BukkitRunnable task, long delay, long period) {
        return scheduleSyncRepeatingTask(plugin, (Runnable) task, delay, period);
    }

    @Override
    @Deprecated
    public int scheduleAsyncDelayedTask(Plugin plugin, Runnable task, long delay) {
        return runTaskLaterAsynchronously(plugin, task, delay).getTaskId();
    }

    @Override
    @Deprecated
    public int scheduleAsyncDelayedTask(Plugin plugin, Runnable task) {
        return runTaskAsynchronously(plugin, task).getTaskId();
    }

    @Override
    @Deprecated
    public int scheduleAsyncRepeatingTask(Plugin plugin, Runnable task, long delay, long period) {
        return runTaskTimerAsynchronously(plugin, task, delay, period).getTaskId();
    }

    // ==================== 任务管理（注册中心驱动） ====================

    @Override
    public void cancelTask(int taskId) {
        CompatBukkitTask task = registry.getById(taskId);
        if (task != null) {
            task.cancel();
            registry.unregister(taskId);
        }
    }

    @Override
    public void cancelTasks(Plugin plugin) {
        registry.cancelAllByPlugin(plugin);
    }

    @Override
    public boolean isCurrentlyRunning(int taskId) {
        CompatBukkitTask task = registry.getById(taskId);
        if (task == null) return false;
        // Folia 没有直接查询任务是否正在执行的 API，
        // 如果任务未取消且有 foliaTask 引用，则认为可能在运行
        return !task.isCancelled();
    }

    @Override
    public boolean isQueued(int taskId) {
        CompatBukkitTask task = registry.getById(taskId);
        return task != null && !task.isCancelled();
    }

    @Override
    public List<BukkitWorker> getActiveWorkers() {
        // Folia 无全局工作线程概念，返回空列表
        return List.of();
    }

    @Override
    public List<BukkitTask> getPendingTasks() {
        return new java.util.ArrayList<>(registry.getPendingTasks());
    }

    @Override
    public Executor getMainThreadExecutor(Plugin plugin) {
        return command -> SchedulerRouter.runGlobalTask(plugin, foliaTask -> command.run());
    }

    // ==================== 内部方法 ====================

    private CompatBukkitTask createTask(Plugin plugin, boolean sync, long period, Runnable work) {
        int id = taskCounter.incrementAndGet();
        CompatBukkitTask task = new CompatBukkitTask(plugin, id, sync, period, work);
        registry.register(task);
        return task;
    }

    @SuppressWarnings("unchecked")
    private CompatBukkitTask createTask(Plugin plugin, boolean sync, long period, Runnable work, Consumer<? super BukkitTask> workConsumer) {
        int id = taskCounter.incrementAndGet();
        CompatBukkitTask task = new CompatBukkitTask(plugin, id, sync, period, work, (Consumer<BukkitTask>) workConsumer);
        registry.register(task);
        return task;
    }

    /**
     * 关闭调度器，取消所有任务。
     */
    public void shutdown() {
        shutdown = true;
        registry.cancelAll();
    }

    /**
     * 获取注册中心引用（供外部清理使用）。
     */
    public TaskRegistry getRegistry() {
        return registry;
    }
}
