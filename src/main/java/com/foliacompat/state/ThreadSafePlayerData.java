package com.foliacompat.state;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的玩家数据容器。
 * 替代插件中常见的 static Map<UUID, SomeData> 模式。
 *
 * 使用示例：
 * <pre>
 *   ThreadSafePlayerData<EconomyData> economy = ThreadSafePlayerData.create("economy");
 *   economy.set(player, new EconomyData(100));
 *   EconomyData data = economy.get(player);
 *   economy.modify(player, d -> { d.addBalance(10); return d; });
 * </pre>
 */
public class ThreadSafePlayerData<V> {

    private static final ConcurrentHashMap<String, ThreadSafePlayerData<?>> INSTANCES = new ConcurrentHashMap<>();

    private final String name;
    private final ConcurrentHashMap<UUID, V> data;

    private ThreadSafePlayerData(String name) {
        this.name = name;
        this.data = new ConcurrentHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static <V> ThreadSafePlayerData<V> create(String name) {
        return (ThreadSafePlayerData<V>) INSTANCES.computeIfAbsent(name, n -> new ThreadSafePlayerData<>(n));
    }

    public V get(Player player) {
        return data.get(player.getUniqueId());
    }

    public V get(UUID uuid) {
        return data.get(uuid);
    }

    public V set(Player player, V value) {
        return data.put(player.getUniqueId(), value);
    }

    public V set(UUID uuid, V value) {
        return data.put(uuid, value);
    }

    public V remove(Player player) {
        return data.remove(player.getUniqueId());
    }

    public V remove(UUID uuid) {
        return data.remove(uuid);
    }

    /**
     * 原子性的读-修改-写操作。
     */
    public V modify(Player player, java.util.function.UnaryOperator<V> modifier) {
        return data.compute(player.getUniqueId(), (uuid, current) -> {
            if (current == null) return null;
            return modifier.apply(current);
        });
    }

    /**
     * 如果不存在则初始化，然后执行操作。
     */
    public V getOrCreate(Player player, java.util.function.Supplier<V> factory) {
        return data.computeIfAbsent(player.getUniqueId(), uuid -> factory.get());
    }

    public boolean has(Player player) {
        return data.containsKey(player.getUniqueId());
    }

    public Map<UUID, V> getAll() {
        return Map.copyOf(data);
    }

    public int size() {
        return data.size();
    }

    public void clear() {
        data.clear();
    }

    public String getName() {
        return name;
    }
}
