package com.foliacompat.state;

import org.bukkit.World;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的世界数据容器。
 * 替代插件中常见的 static Map<String, SomeData> 或按世界名索引的模式。
 */
public class ThreadSafeWorldData<V> {

    private static final ConcurrentHashMap<String, ThreadSafeWorldData<?>> INSTANCES = new ConcurrentHashMap<>();

    private final String name;
    private final ConcurrentHashMap<UUID, V> data;

    private ThreadSafeWorldData(String name) {
        this.name = name;
        this.data = new ConcurrentHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static <V> ThreadSafeWorldData<V> create(String name) {
        return (ThreadSafeWorldData<V>) INSTANCES.computeIfAbsent(name, n -> new ThreadSafeWorldData<>(n));
    }

    public V get(World world) {
        return data.get(world.getUID());
    }

    public V set(World world, V value) {
        return data.put(world.getUID(), value);
    }

    public V remove(World world) {
        return data.remove(world.getUID());
    }

    public V modify(World world, java.util.function.UnaryOperator<V> modifier) {
        return data.compute(world.getUID(), (uuid, current) -> {
            if (current == null) return null;
            return modifier.apply(current);
        });
    }

    public V getOrCreate(World world, java.util.function.Supplier<V> factory) {
        return data.computeIfAbsent(world.getUID(), uuid -> factory.get());
    }

    public Map<UUID, V> getAll() {
        return Map.copyOf(data);
    }

    public void clear() {
        data.clear();
    }
}
