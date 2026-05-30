package com.foliacompat.agent;

import java.io.*;
import java.nio.file.*;
import java.util.jar.*;
import java.util.logging.Logger;
import java.util.zip.*;

/**
 * Plugin YAML patcher.
 *
 * Scans the plugins directory during Agent premain stage and injects
 * folia-supported: true into all plugin jars that lack this field.
 *
 * This is more reliable than ASM bytecode modification of SpigotPluginProviderFactory,
 * as it doesn't depend on Folia internal class structure and avoids ClassLoader / COMPUTE_FRAMES issues.
 */
public class PluginYamlPatcher {

    private static final Logger LOGGER = Logger.getLogger("FoliaCompat-Agent");

    // Folia native plugin keywords - plugins with these keywords in filename
    // already support Folia natively and should not be patched
    private static final String[] FOLIA_NATIVE_KEYWORDS = {
            "PlaceholderAPI",
            "Citizens",
            "TAB",
            "CoreProtect",
            "Chunky",
            "GrimAC",
            "PacketEvents",
            "VeinMiner",
            "Plan",
            "GSit",
            "AxGraves",
            "FastAsyncWorldEdit",
            "FAWE",
            "WorldGuard",
            "DiscordSRV",
            "Terra",
            "Orebfuscator",
            "ChunkyBorder"
    };

    /**
     * Scan plugins directory and inject folia-supported: true into all plugins.
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
     * Inject folia-supported: true into a single plugin jar.
     * If plugin.yml already has this field, skip.
     *
     * @return true if modifications were made
     */
    private static boolean patchPluginJar(Path jarPath) {
        String jarName = jarPath.getFileName().toString();

        // Skip FoliaCompat itself
        if (jarName.contains("folia-compat")) return false;

        // Skip Folia native plugins
        String jarNameLower = jarName.toLowerCase();
        for (String keyword : FOLIA_NATIVE_KEYWORDS) {
            if (jarNameLower.contains(keyword.toLowerCase())) {
                LOGGER.fine("[FoliaCompat-Agent] Skipping Folia native plugin: " + jarName);
                return false;
            }
        }

        try {
            // Try to read plugin.yml first
            String pluginYml = readPluginYml(jarPath);

            if (pluginYml == null) {
                // No plugin.yml found - check for paper-plugin.yml (Paper's new format)
                String paperPluginYml = readPaperPluginYml(jarPath);
                if (paperPluginYml != null) {
                    // Has paper-plugin.yml, patch that instead
                    if (paperPluginYml.contains("folia-supported")) return false;
                    String patchedYml = injectFoliaSupported(paperPluginYml);
                    updatePaperPluginYml(jarPath, patchedYml);
                    LOGGER.info("[FoliaCompat-Agent] Injected folia-supported: true into paper-plugin.yml of " + jarName);
                    return true;
                }

                // Neither plugin.yml nor paper-plugin.yml found
                // This might be a broken jar (like BetterTeams) - try to create a minimal plugin.yml
                // by inspecting the jar for clues
                if (tryCreateMinimalPluginYml(jarPath)) {
                    LOGGER.info("[FoliaCompat-Agent] Created minimal plugin.yml with folia-supported: true for " + jarName);
                    return true;
                }

                return false; // No YAML to patch
            }

            if (pluginYml.contains("folia-supported")) return false; // Already has it

            // Inject folia-supported: true
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
     * Read plugin.yml from jar.
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
     * Read paper-plugin.yml from jar (Paper's new plugin format).
     */
    private static String readPaperPluginYml(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            // Check both root and META-INF locations
            for (String path : new String[]{"paper-plugin.yml", "META-INF/paper-plugin.yml"}) {
                JarEntry entry = jar.getJarEntry(path);
                if (entry != null) {
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
            return null;
        }
    }

    /**
     * Try to create a minimal plugin.yml for jars that don't have one.
     * This handles cases like BetterTeams where the jar is missing plugin.yml entirely.
     *
     * @return true if a plugin.yml was created
     */
    private static boolean tryCreateMinimalPluginYml(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            // Look for class files to infer the main class
            String mainClass = null;
            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                // Look for common plugin main class patterns
                if (name.endsWith("Plugin.class") && !name.contains("$") && !name.startsWith("com/foliacompat/")) {
                    // Convert path to class name
                    mainClass = name.replace(".class", "").replace("/", ".");
                    break;
                }
            }

            if (mainClass == null) {
                // Try another pattern - look for any class that extends JavaPlugin
                entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.endsWith(".class") && !name.contains("$") && !name.startsWith("META-INF/")) {
                        // Read class bytes to check if it extends JavaPlugin
                        try (InputStream is = jar.getInputStream(entry)) {
                            byte[] classBytes = is.readAllBytes();
                            String classStr = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                            if (classStr.contains("JavaPlugin") || classStr.contains("onEnable")) {
                                mainClass = name.replace(".class", "").replace("/", ".");
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            if (mainClass == null) return false;

            // Extract plugin name from jar filename
            String jarName = jarPath.getFileName().toString();
            String pluginName = jarName.replaceAll("-[0-9].*\\.jar$", "").replaceAll("\\.jar$", "");

            // Create minimal plugin.yml
            String minimalYml = "name: " + pluginName + "\n"
                    + "version: '1.0'\n"
                    + "main: " + mainClass + "\n"
                    + "api-version: '1.21'\n"
                    + "folia-supported: true\n";

            // Write to jar
            updatePluginYmlContent(jarPath, minimalYml, "plugin.yml");
            LOGGER.info("[FoliaCompat-Agent] Created minimal plugin.yml for " + jarName + " (main: " + mainClass + ")");
            return true;

        } catch (Exception e) {
            LOGGER.fine("[FoliaCompat-Agent] Could not create minimal plugin.yml for " + jarPath.getFileName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Inject folia-supported: true into YAML content.
     * Inserts after api-version line or at end of file.
     */
    private static String injectFoliaSupported(String yml) {
        // Try to insert after api-version line
        int idx = yml.indexOf("api-version");
        if (idx >= 0) {
            // Find end of that line
            int lineEnd = yml.indexOf('\n', idx);
            if (lineEnd < 0) lineEnd = yml.length();

            String insertion = "\nfolia-supported: true";
            return yml.substring(0, lineEnd) + insertion + yml.substring(lineEnd);
        }

        // No api-version, append to end
        if (!yml.endsWith("\n")) {
            yml += "\n";
        }
        return yml + "folia-supported: true\n";
    }

    /**
     * Update plugin.yml in jar.
     * Rewrites entire jar (JarFile doesn't support in-place modification).
     */
    private static void updatePluginYml(Path jarPath, String patchedYml) throws IOException {
        updatePluginYmlContent(jarPath, patchedYml, "plugin.yml");
    }

    /**
     * Update paper-plugin.yml in jar.
     */
    private static void updatePaperPluginYml(Path jarPath, String patchedYml) throws IOException {
        // Try root location first, then META-INF
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            if (jar.getJarEntry("paper-plugin.yml") != null) {
                updatePluginYmlContent(jarPath, patchedYml, "paper-plugin.yml");
            } else {
                updatePluginYmlContent(jarPath, patchedYml, "META-INF/paper-plugin.yml");
            }
        }
    }

    /**
     * Generic method to update a YAML file inside a jar.
     */
    private static void updatePluginYmlContent(Path jarPath, String patchedYml, String ymlPath) throws IOException {
        Path tempJar = jarPath.resolveSibling(jarPath.getFileName() + ".tmp");

        try (JarFile jar = new JarFile(jarPath.toFile());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(tempJar))) {

            byte[] patchedBytes = patchedYml.getBytes("UTF-8");

            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.equals(ymlPath)) {
                    // Write modified YAML
                    JarEntry newEntry = new JarEntry(name);
                    jos.putNextEntry(newEntry);
                    jos.write(patchedBytes);
                    jos.closeEntry();
                } else if (!name.equals("META-INF/MANIFEST.MF")) {
                    // Copy other entries
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

        // Atomic replace
        Files.move(tempJar, jarPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
