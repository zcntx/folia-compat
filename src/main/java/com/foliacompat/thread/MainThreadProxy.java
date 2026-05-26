package com.foliacompat.thread;

import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * 修正 isPrimaryThread() 行为。
 * 在 Folia 中，区域线程也应该被视为"主线程"，因为它们承担了原主线程的游戏逻辑职责。
 *
 * 反射 Method 对象会被缓存，避免每次调用都进行反射查找。
 */
public final class MainThreadProxy {

    private static final Logger LOGGER = Logger.getLogger("FoliaCompat");
    private static volatile boolean installed = false;

    // 缓存的反射方法
    private static volatile Method isTickThreadForMethod = null;
    private static volatile Method isGlobalTickThreadMethod = null;
    private static volatile boolean reflectionInitialized = false;

    private MainThreadProxy() {}

    public static void install() {
        if (installed) return;
        synchronized (MainThreadProxy.class) {
            if (installed) return;
            initReflection();
            installed = true;
            LOGGER.info("[FoliaCompat] MainThreadProxy installed - region threads will report as primary thread");
        }
    }

    /**
     * 初始化并缓存反射 Method 对象。
     */
    private static void initReflection() {
        if (reflectionInitialized) return;
        try {
            Class<?> regionizedServerClass = Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            try {
                isTickThreadForMethod = regionizedServerClass.getMethod("isTickThreadFor", Object.class);
            } catch (NoSuchMethodException ignored) {}

            try {
                isGlobalTickThreadMethod = regionizedServerClass.getMethod("isGlobalTickThread");
            } catch (NoSuchMethodException ignored) {}
        } catch (ClassNotFoundException ignored) {}
        reflectionInitialized = true;
    }

    /**
     * 替代 Bukkit.isPrimaryThread() 的判断。
     * 在 Folia 区域线程中也返回 true。
     */
    public static boolean isPrimaryThread() {
        if (!com.foliacompat.util.FoliaDetector.isFolia()) {
            return Bukkit.isPrimaryThread();
        }

        // 优先使用缓存的反射方法
        Method method = isTickThreadForMethod;
        if (method != null) {
            try {
                return (boolean) method.invoke(null, Thread.currentThread());
            } catch (Exception ignored) {}
        }

        // 反射不可用时的回退方案
        String threadName = Thread.currentThread().getName();
        return threadName.contains("Region Scheduler")
                || threadName.contains("region")
                || threadName.contains("Global Region")
                || Bukkit.isPrimaryThread();
    }

    /**
     * 判断当前线程是否为全局区域线程（如全局 tick 线程）。
     */
    public static boolean isGlobalRegionThread() {
        Method method = isGlobalTickThreadMethod;
        if (method != null) {
            try {
                return (boolean) method.invoke(null);
            } catch (Exception ignored) {}
        }
        return Thread.currentThread().getName().contains("Global Region");
    }

    public static boolean isInstalled() {
        return installed;
    }
}
