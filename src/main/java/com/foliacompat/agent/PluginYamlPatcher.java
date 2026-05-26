package com.foliacompat.agent;

import java.io.*;
import java.nio.file.*;
import java.util.jar.*;
import java.util.logging.Logger;
import java.util.zip.*;

/**
 * 插件 YAML 补丁工具。
 *
 * 在 Agent premain 阶段扫描 plugins 目录，
 * 为所有缺少 folia-supported 字段的插件 jar 注入 folia-supported: true。
 *
 * 这比 ASM 字节码修改 SpigotPluginProviderFactory 更可靠，
 * 因为不依赖 Folia 内部类结构，也不会遇到 ClassLoader / COMPUTE_FRAMES 问题。
 */
public class PluginYamlPatcher {

    private static final Logger LOGGER = Logger.getLogger("FoliaCompat-Agent");

    /**
     * 扫描 plugins 目录，为所有插件注入 folia-supported: true。
     */
    public static void patchPlugins(String pluginsDir) {
        if (pluginsDir == null) return;

        Path dir = Paths.get(pluginsDir);
        if (!Files.isDirectory(dir)) return;

        int patched = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path jarPath : stream) {
                if (patchPluginJar(jarPath)) {
                    patched++;
                }
            }
        } catch (Exception e) {
            LOGGER.warning("[FoliaCompat-Agent] Error scanning plugins directory: " + e.getMessage());
        }

        if (patched > 0) {
            LOGGER.info("[FoliaCompat-Agent] Patched " + patched + " plugin(s) with folia-supported: true");
        }
    }

    /**
     * 为单个插件 jar 注入 folia-supported: true。
     * 如果 plugin.yml 中已有此字段则跳过。
     *
     * @return true 如果进行了修改
     */
    private static boolean patchPluginJar(Path jarPath) {
        String jarName = jarPath.getFileName().toString();

        // 跳过 FoliaCompat 自身
        if (jarName.contains("folia-compat")) return false;

        try {
            // 先读取 plugin.yml 检查是否已有 folia-supported
            String pluginYml = readPluginYml(jarPath);
            if (pluginYml == null) return false; // 无 plugin.yml，跳过

            if (pluginYml.contains("folia-supported")) return false; // 已有，跳过

            // 注入 folia-supported: true
            String patchedYml = injectFoliaSupported(pluginYml);
            updatePluginYml(jarPath, patchedYml);
            LOGGER.info("[FoliaCompat-Agent] Injected folia-supported: true into " + jarName);
            return true;

        } catch (Exception e) {
            LOGGER.warning("[FoliaCompat-Agent] Failed to patch " + jarName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 从 jar 中读取 plugin.yml 内容。
     */
    private static String readPluginYml(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry("plugin.yml");
            if (entry == null) return null;

            try (InputStream is = jar.getInputStream(entry);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = is.read(buffer)) != -1) {
                    bos.write(buffer, 0, n);
                }
                return bos.toString("UTF-8");
            }
        }
    }

    /**
     * 在 plugin.yml 中注入 folia-supported: true。
     * 在 api-version 行之后或文件末尾添加。
     */
    private static String injectFoliaSupported(String yml) {
        // 尝试在 api-version 行后插入
        int idx = yml.indexOf("api-version");
        if (idx >= 0) {
            // 找到该行结尾
            int lineEnd = yml.indexOf('\n', idx);
            if (lineEnd < 0) lineEnd = yml.length();

            // 计算缩进（与 api-version 对齐）
            int lineStart = yml.lastIndexOf('\n', idx);
            if (lineStart < 0) lineStart = 0;
            else lineStart++;

            String indent = yml.substring(lineStart, idx);
            // 去掉可能的 "- " 前缀，只保留空格
            indent = indent.replaceAll("[^ \t]", "");

            String insertion = "\nfolia-supported: true";
            return yml.substring(0, lineEnd) + insertion + yml.substring(lineEnd);
        }

        // 没有 api-version，在文件末尾添加
        if (!yml.endsWith("\n")) {
            yml += "\n";
        }
        return yml + "folia-supported: true\n";
    }

    /**
     * 更新 jar 中的 plugin.yml。
     * 通过重写整个 jar 实现（因为 JarFile 不支持原地修改）。
     */
    private static void updatePluginYml(Path jarPath, String patchedYml) throws IOException {
        Path tempJar = jarPath.resolveSibling(jarPath.getFileName() + ".tmp");

        try (JarFile jar = new JarFile(jarPath.toFile());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(tempJar))) {

            byte[] patchedBytes = patchedYml.getBytes("UTF-8");

            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.equals("plugin.yml")) {
                    // 写入修改后的 plugin.yml
                    JarEntry newEntry = new JarEntry(name);
                    jos.putNextEntry(newEntry);
                    jos.write(patchedBytes);
                    jos.closeEntry();
                } else if (!name.equals("META-INF/MANIFEST.MF")) {
                    // 复制其他条目
                    JarEntry newEntry = new JarEntry(name);
                    jos.putNextEntry(newEntry);

                    try (InputStream is = jar.getInputStream(entry)) {
                        byte[] buffer = new byte[4096];
                        int n;
                        while ((n = is.read(buffer)) != -1) {
                            jos.write(buffer, 0, n);
                        }
                    }
                    jos.closeEntry();
                }
            }
        }

        // 原子替换
        Files.move(tempJar, jarPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
