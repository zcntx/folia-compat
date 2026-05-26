package com.foliacompat.entity;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import com.foliacompat.scheduler.SchedulerRouter;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 跨区域实体操作安全桥。
 *
 * 解决问题：在 Folia 中，直接操作不属于当前区域线程的实体是不安全的。
 * 这个桥接层确保实体操作在其所属区域线程上执行。
 */
public class CrossRegionEntityBridge {

    private static final Logger LOGGER = Logger.getLogger("FoliaCompat");
    private static final Map<UUID, WeakReference<Entity>> entityCache = new ConcurrentHashMap<>();

    /**
     * 安全地在实体所属区域执行操作。
     * 如果实体在当前区域，直接执行；否则调度到目标区域。
     */
    public static void runOnEntityRegion(Plugin plugin, Entity entity, Consumer<Entity> action) {
        if (entity == null || !entity.isValid()) {
            LOGGER.warning("[FoliaCompat] Attempted to operate on null/invalid entity, skipping");
            return;
        }

        SchedulerRouter.runEntityTask(plugin, entity, task -> {
            if (entity.isValid()) {
                action.accept(entity);
            }
        });
    }

    /**
     * 安全传送实体到目标位置。
     * 在目标位置的区域线程上执行传送。
     */
    public static void safeTeleport(Plugin plugin, Entity entity, Location destination) {
        if (entity == null || destination == null) return;

        SchedulerRouter.runRegionTask(plugin, destination, task -> {
            if (entity.isValid()) {
                entity.teleport(destination);
            }
        });
    }

    /**
     * 跨世界安全传送。
     */
    public static void safeCrossWorldTeleport(Plugin plugin, Entity entity, World targetWorld, Location destination) {
        if (entity == null || targetWorld == null) return;

        Location dest = destination != null ? destination : targetWorld.getSpawnLocation();
        SchedulerRouter.runRegionTask(plugin, dest, task -> {
            if (entity.isValid()) {
                entity.teleport(dest);
            }
        });
    }

    /**
     * 批量实体操作 - 按区域分组并行执行。
     * 将实体按其所在区域分组，每组在其区域线程上并行执行。
     */
    public static void batchEntityOperation(Plugin plugin, Iterable<Entity> entities, Consumer<Entity> action) {
        for (Entity entity : entities) {
            if (entity != null && entity.isValid()) {
                runOnEntityRegion(plugin, entity, action);
            }
        }
    }

    /**
     * 安全获取实体引用。
     * 返回弱引用，调用者需要检查实体是否仍然有效。
     */
    public static WeakReference<Entity> cacheEntity(Entity entity) {
        WeakReference<Entity> ref = new WeakReference<>(entity);
        entityCache.put(entity.getUniqueId(), ref);
        return ref;
    }

    public static Entity getCachedEntity(UUID uuid) {
        WeakReference<Entity> ref = entityCache.get(uuid);
        return ref != null ? ref.get() : null;
    }

    /**
     * 移除指定实体的缓存。
     */
    public static void evict(UUID uuid) {
        entityCache.remove(uuid);
    }

    /**
     * 清空所有缓存（仅插件关闭时使用）。
     */
    public static void clearAllCache() {
        entityCache.clear();
    }
}
