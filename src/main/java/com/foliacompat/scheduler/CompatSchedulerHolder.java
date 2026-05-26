package com.foliacompat.scheduler;

import org.bukkit.scheduler.BukkitScheduler;

/**
 * 持有全局 CompatScheduler 实例的静态持有者。
 * 被字节码转换器注入的代码调用，替代 Bukkit.getScheduler()。
 */
public final class CompatSchedulerHolder {

    private static final CompatScheduler INSTANCE = new CompatScheduler();

    private CompatSchedulerHolder() {}

    /**
     * 返回兼容的 BukkitScheduler 实例。
     * 字节码转换器会将 Bukkit.getScheduler() 的调用重定向到这里。
     */
    public static BukkitScheduler getScheduler() {
        return INSTANCE;
    }

    /**
     * 关闭调度器，取消所有注册任务。
     * 插件 onDisable 时调用。
     */
    public static void shutdown() {
        INSTANCE.shutdown();
    }
}
