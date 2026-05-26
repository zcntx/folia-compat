package com.foliacompat.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 根据调用上下文，将任务路由到正确的 Folia 调度器。
 */
public final class SchedulerRouter {

    private SchedulerRouter() {}

    /**
     * 路由到 EntityScheduler（有实体上下文时）。
     */
    public static void runEntityTask(Plugin plugin, Entity entity, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task) {
        EntityScheduler scheduler = entity.getScheduler();
        scheduler.run(plugin, t -> task.accept(t), null);
    }

    public static void runEntityTaskDelayed(Plugin plugin, Entity entity, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task, long delayTicks) {
        EntityScheduler scheduler = entity.getScheduler();
        if (delayTicks <= 0) {
            scheduler.run(plugin, t -> task.accept(t), null);
        } else {
            scheduler.runDelayed(plugin, t -> task.accept(t), null, delayTicks);
        }
    }

    /**
     * 路由到 RegionScheduler（有 Location 上下文时）。
     */
    public static void runRegionTask(Plugin plugin, Location location, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task) {
        RegionScheduler scheduler = Bukkit.getRegionScheduler();
        scheduler.run(plugin, location, t -> task.accept(t));
    }

    public static void runRegionTaskDelayed(Plugin plugin, Location location, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task, long delayTicks) {
        RegionScheduler scheduler = Bukkit.getRegionScheduler();
        if (delayTicks <= 0) {
            scheduler.run(plugin, location, t -> task.accept(t));
        } else {
            scheduler.runDelayed(plugin, location, t -> task.accept(t), delayTicks);
        }
    }

    /**
     * 路由到 GlobalRegionScheduler（全局任务，如配置操作、广播）。
     */
    public static void runGlobalTask(Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task) {
        GlobalRegionScheduler scheduler = Bukkit.getGlobalRegionScheduler();
        scheduler.run(plugin, t -> task.accept(t));
    }

    public static void runGlobalTaskDelayed(Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task, long delayTicks) {
        GlobalRegionScheduler scheduler = Bukkit.getGlobalRegionScheduler();
        if (delayTicks <= 0) {
            // Folia's runDelayed does not accept delay <= 0, fall back to immediate execution
            scheduler.run(plugin, t -> task.accept(t));
        } else {
            scheduler.runDelayed(plugin, t -> task.accept(t), delayTicks);
        }
    }

    /**
     * 路由到 AsyncScheduler（纯异步任务，不涉及游戏状态修改）。
     */
    public static void runAsyncTask(Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task) {
        AsyncScheduler scheduler = Bukkit.getAsyncScheduler();
        scheduler.runNow(plugin, t -> task.accept(t));
    }

    public static void runAsyncTaskDelayed(Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task, long delayTicks) {
        AsyncScheduler scheduler = Bukkit.getAsyncScheduler();
        if (delayTicks <= 0) {
            scheduler.runNow(plugin, t -> task.accept(t));
        } else {
            scheduler.runDelayed(plugin, t -> task.accept(t), delayTicks * 50, TimeUnit.MILLISECONDS);
        }
    }

    public static void runAsyncTaskRepeating(Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task, long delayTicks, long periodTicks) {
        AsyncScheduler scheduler = Bukkit.getAsyncScheduler();
        long delayMs = Math.max(1, delayTicks) * 50;
        long periodMs = Math.max(1, periodTicks) * 50;
        scheduler.runAtFixedRate(plugin, t -> task.accept(t), delayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 智能路由：无特定上下文时，根据 Runnable 类型选择调度器。
     * 默认路由到 GlobalRegionScheduler（最安全的选择）。
     */
    public static void runDefault(Plugin plugin, Runnable task) {
        runGlobalTask(plugin, t -> task.run());
    }
}
