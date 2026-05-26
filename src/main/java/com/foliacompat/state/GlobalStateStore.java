package com.foliacompat.state;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 全局线程安全状态存储。
 * 插件可以用它替代自己管理的 static Map/HashMap，避免多区域线程竞争。
 *
 * 底层使用 ConcurrentHashMap，已提供所有单键操作的线程安全性。
 * 复合操作使用 compute()/computeIfAbsent() 等原子方法。
 *
 * 使用示例：
 * <pre>
 *   GlobalStateStore<String, PlayerData> store = GlobalStateStore.create("playerData");
 *   store.put(playerUUID.toString(), new PlayerData(...));
 *   PlayerData data = store.get(playerUUID.toString());
 * </pre>
 */
public class GlobalStateStore<K, V> {

    private static final ConcurrentHashMap<String, GlobalStateStore<?, ?>> STORES = new ConcurrentHashMap<>();

    private final String name;
    private final ConcurrentHashMap<K, V> data;

    private GlobalStateStore(String name) {
        this.name = name;
        this.data = new ConcurrentHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static <K, V> GlobalStateStore<K, V> create(String name) {
        return (GlobalStateStore<K, V>) STORES.computeIfAbsent(name, n -> new GlobalStateStore<>(n));
    }

    @SuppressWarnings("unchecked")
    public static <K, V> GlobalStateStore<K, V> get(String name) {
        return (GlobalStateStore<K, V>) STORES.get(name);
    }

    public V get(K key) {
        return data.get(key);
    }

    public V put(K key, V value) {
        return data.put(key, value);
    }

    public V remove(K key) {
        return data.remove(key);
    }

    public boolean containsKey(K key) {
        return data.containsKey(key);
    }

    public int size() {
        return data.size();
    }

    /**
     * 原子性的 compute-if-absent 操作。
     */
    public V computeIfAbsent(K key, Function<K, V> mappingFunction) {
        return data.computeIfAbsent(key, mappingFunction);
    }

    /**
     * 原子性的 compute 操作（读-改-写）。
     */
    public V compute(K key, java.util.function.BiFunction<K, V, V> remappingFunction) {
        return data.compute(key, remappingFunction);
    }

    /**
     * 原子性的 put-if-absent 操作。
     */
    public V putIfAbsent(K key, V value) {
        return data.putIfAbsent(key, value);
    }

    /**
     * 原子性的 replace 操作。
     */
    public boolean replace(K key, V oldValue, V newValue) {
        return data.replace(key, oldValue, newValue);
    }

    public String getName() {
        return name;
    }

    public void clear() {
        data.clear();
    }

    public static void clearAll() {
        STORES.values().forEach(GlobalStateStore::clear);
        STORES.clear();
    }
}
