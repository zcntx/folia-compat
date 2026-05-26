package com.foliacompat.agent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * Java Agent 入口。
 * 在 JVM 启动时通过 -javaagent 参数加载，注册字节码转换器。
 *
 * 使用方式：
 * java -javaagent:folia-compat.jar -jar folia-server.jar
 */
public class FoliaCompatAgent {

    private static final Logger LOGGER = Logger.getLogger("FoliaCompat-Agent");
    private static Instrumentation instrumentation;

    /**
     * JVM 启动时调用（-javaagent 模式）。
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        LOGGER.info("[FoliaCompat-Agent] Initializing bytecode transformation layer...");
        install(inst);
    }

    /**
     * 动态附加时调用（attach API 模式）。
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        LOGGER.info("[FoliaCompat-Agent] Dynamically attached, installing transformer...");
        install(inst);
    }

    private static void install(Instrumentation inst) {
        // 为 plugins 目录下的插件注入 folia-supported: true
        String pluginsDir = detectPluginsDir();
        if (pluginsDir != null) {
            PluginYamlPatcher.patchPlugins(pluginsDir);
        }

        // 注册调度器转换器（拦截 Bukkit.getScheduler() / isPrimaryThread()）
        SchedulerTransformer transformer = new SchedulerTransformer();
        inst.addTransformer(transformer, true);
        LOGGER.info("[FoliaCompat-Agent] SchedulerTransformer registered successfully");
        LOGGER.info("[FoliaCompat-Agent] Will intercept: Bukkit.getScheduler(), Bukkit.isPrimaryThread()");
    }

    /**
     * 检测 plugins 目录路径。
     * 从当前工作目录下的 plugins/ 子目录获取。
     */
    private static String detectPluginsDir() {
        String dir = System.getProperty("user.dir");
        if (dir != null) {
            Path pluginsPath = Paths.get(dir, "plugins");
            if (Files.isDirectory(pluginsPath)) {
                return pluginsPath.toString();
            }
        }
        return null;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }
}
