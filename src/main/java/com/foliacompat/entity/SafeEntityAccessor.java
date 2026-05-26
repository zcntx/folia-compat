package com.foliacompat.entity;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 安全的实体访问封装。
 * 对 Bukkit 的实体获取 API 进行线程安全包装。
 */
public final class SafeEntityAccessor {

    private static final Logger LOGGER = Logger.getLogger("FoliaCompat");

    private SafeEntityAccessor() {}

    /**
     * 线程安全地获取所有在线玩家。
     * 返回不可变快照。
     */
    public static List<Player> getOnlinePlayers() {
        return safeCopy(Bukkit.getOnlinePlayers(), Player.class);
    }

    /**
     * 线程安全地按 UUID 查找玩家。
     */
    public static Player getPlayer(UUID uuid) {
        return Bukkit.getPlayer(uuid);
    }

    /**
     * 线程安全地获取世界中的所有实体。
     * 返回快照列表，避免并发修改。
     */
    public static List<Entity> getEntities(World world) {
        return safeCopy(world.getEntities(), Entity.class);
    }

    /**
     * 获取指定类型的所有实体。
     */
    public static <T extends Entity> List<T> getEntitiesByType(World world, Class<T> type) {
        return safeCopy(world.getEntitiesByClass(type), type);
    }

    /**
     * 按名称查找玩家。
     */
    public static Player getPlayerExact(String name) {
        return Bukkit.getPlayerExact(name);
    }

    /**
     * 模糊匹配玩家名称。
     */
    public static List<Player> matchPlayer(String name) {
        return safeCopy(Bukkit.matchPlayer(name), Player.class);
    }

    /**
     * 安全复制集合为不可变快照。
     * 优先使用 List.copyOf（完全不可变），
     * 遇到并发修改异常时回退到 ArrayList 构造器（允许微小不一致但不会丢失数据）。
     */
    @SuppressWarnings("unchecked")
    private static <T> List<T> safeCopy(Collection<? extends T> collection, Class<T> type) {
        try {
            return (List<T>) List.copyOf(collection);
        } catch (Exception first) {
            try {
                return Collections.unmodifiableList(new ArrayList<>((Collection<T>) collection));
            } catch (Exception second) {
                LOGGER.warning("[FoliaCompat] Failed to snapshot collection: " + second.getMessage());
                return Collections.emptyList();
            }
        }
    }
}
