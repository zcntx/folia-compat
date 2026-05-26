package com.foliacompat.thread;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import com.foliacompat.scheduler.SchedulerRouter;

/**
 * 区域感知的 Runnable 包装。
 * 将旧插件中直接 runTask 的逻辑包装为在正确区域线程上执行。
 *
 * 使用方式：
 * <pre>
 *   // 旧代码:
 *   new BukkitRunnable() {
 *       public void run() { /* 操作游戏状态 *\/ }
 *   }.runTask(plugin);
 *
 *   // 兼容代码:
 *   RegionAwareRunnable.create(plugin)
 *       .atLocation(someLocation)
 *       .withEntity(someEntity)
 *       .run(() -> { /* 操作游戏状态 *\/ });
 * </pre>
 */
public class RegionAwareRunnable {

    private final Plugin plugin;
    private Location location;
    private Entity entity;
    private long delay = 0;

    private RegionAwareRunnable(Plugin plugin) {
        this.plugin = plugin;
    }

    public static RegionAwareRunnable create(Plugin plugin) {
        return new RegionAwareRunnable(plugin);
    }

    public RegionAwareRunnable atLocation(Location location) {
        this.location = location;
        return this;
    }

    public RegionAwareRunnable withEntity(Entity entity) {
        this.entity = entity;
        return this;
    }

    public RegionAwareRunnable delayTicks(long delay) {
        this.delay = delay;
        return this;
    }

    /**
     * 在合适的区域线程上执行任务。
     * 优先级：Entity > Location > Global
     */
    public void run(Runnable task) {
        if (entity != null && entity.isValid()) {
            if (delay > 0) {
                SchedulerRouter.runEntityTaskDelayed(plugin, entity, t -> task.run(), delay);
            } else {
                SchedulerRouter.runEntityTask(plugin, entity, t -> task.run());
            }
        } else if (location != null) {
            if (delay > 0) {
                SchedulerRouter.runRegionTaskDelayed(plugin, location, t -> task.run(), delay);
            } else {
                SchedulerRouter.runRegionTask(plugin, location, t -> task.run());
            }
        } else {
            if (delay > 0) {
                SchedulerRouter.runGlobalTaskDelayed(plugin, t -> task.run(), delay);
            } else {
                SchedulerRouter.runGlobalTask(plugin, t -> task.run());
            }
        }
    }

    /**
     * 在异步线程上执行任务。
     */
    public void runAsync(Runnable task) {
        if (delay > 0) {
            SchedulerRouter.runAsyncTaskDelayed(plugin, t -> task.run(), delay);
        } else {
            SchedulerRouter.runAsyncTask(plugin, t -> task.run());
        }
    }
}
