package com.foliacompat.scheduler;

import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 任务注册中心。
 * 维护 taskId → task 和 plugin → tasks 的双索引，
 * 支持 cancelTask(id)、cancelTasks(plugin)、getPendingTasks() 等操作。
 */
public class TaskRegistry {

    private final ConcurrentHashMap<Integer, CompatBukkitTask> tasksById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Plugin, Set<CompatBukkitTask>> tasksByPlugin = new ConcurrentHashMap<>();

    public void register(CompatBukkitTask task) {
        tasksById.put(task.getTaskId(), task);
        tasksByPlugin.computeIfAbsent(task.getOwner(), p -> new CopyOnWriteArraySet<>()).add(task);
    }

    public void unregister(int taskId) {
        CompatBukkitTask removed = tasksById.remove(taskId);
        if (removed != null) {
            Set<CompatBukkitTask> set = tasksByPlugin.get(removed.getOwner());
            if (set != null) {
                set.remove(removed);
                if (set.isEmpty()) {
                    tasksByPlugin.remove(removed.getOwner(), set);
                }
            }
        }
    }

    public CompatBukkitTask getById(int taskId) {
        return tasksById.get(taskId);
    }

    public List<CompatBukkitTask> getByPlugin(Plugin plugin) {
        Set<CompatBukkitTask> set = tasksByPlugin.get(plugin);
        return set != null ? new ArrayList<>(set) : List.of();
    }

    /**
     * 移除所有已取消的任务，释放内存。
     */
    public void purgeCancelled() {
        tasksById.entrySet().removeIf(entry -> {
            if (entry.getValue().isCancelled()) {
                Set<CompatBukkitTask> set = tasksByPlugin.get(entry.getValue().getOwner());
                if (set != null) {
                    set.remove(entry.getValue());
                    if (set.isEmpty()) {
                        tasksByPlugin.remove(entry.getValue().getOwner(), set);
                    }
                }
                return true;
            }
            return false;
        });
    }

    /**
     * 取消指定插件的所有任务。
     */
    public void cancelAllByPlugin(Plugin plugin) {
        Set<CompatBukkitTask> set = tasksByPlugin.remove(plugin);
        if (set != null) {
            for (CompatBukkitTask task : set) {
                task.cancel();
                tasksById.remove(task.getTaskId());
            }
        }
    }

    /**
     * 取消所有任务（插件关闭时使用）。
     */
    public void cancelAll() {
        for (Set<CompatBukkitTask> set : tasksByPlugin.values()) {
            for (CompatBukkitTask task : set) {
                task.cancel();
            }
        }
        tasksById.clear();
        tasksByPlugin.clear();
    }

    public int size() {
        return tasksById.size();
    }

    /**
     * 返回尚未取消的任务数量。
     */
    public int pendingCount() {
        int count = 0;
        for (CompatBukkitTask task : tasksById.values()) {
            if (!task.isCancelled()) count++;
        }
        return count;
    }

    /**
     * 返回所有未取消的任务列表。
     */
    public List<CompatBukkitTask> getPendingTasks() {
        List<CompatBukkitTask> result = new ArrayList<>();
        for (CompatBukkitTask task : tasksById.values()) {
            if (!task.isCancelled()) result.add(task);
        }
        return result;
    }
}
