package com.foliacompat.compat;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Thread context bridge for plugins that rely on thread-local world data.
 *
 * In Folia, there is no single "main thread" — each region runs on its own thread.
 * Plugins like Multiverse-Core use ThreadLocal or static context to track the
 * "current world", which returns null in Folia's regionized model.
 *
 * This bridge intercepts common patterns and provides sensible defaults:
 * - getCurrentWorldData() → returns data for the first available world
 * - Static world context → falls back to overworld
 */
public final class ThreadContextBridge {

    private static final Logger LOGGER = Logger.getLogger("FoliaCompat");
    private static volatile boolean initialized = false;

    // Cached reflection targets
    private static volatile Class<?> mvWorldManagerClass = null;
    private static volatile Object mvWorldManagerInstance = null;

    private ThreadContextBridge() {}

    public static void initialize() {
        if (initialized) return;
        synchronized (ThreadContextBridge.class) {
            if (initialized) return;

            // Try to find Multiverse-Core's WorldManager
            try {
                mvWorldManagerClass = Class.forName("com.onarandombox.MultiverseCore.world.WorldManager");
                LOGGER.info("[FoliaCompat] ThreadContextBridge: Found Multiverse-Core WorldManager");
            } catch (ClassNotFoundException e) {
                // Multiverse-Core not installed, that's fine
                mvWorldManagerClass = null;
            }

            initialized = true;
            LOGGER.info("[FoliaCompat] ThreadContextBridge initialized");
        }
    }

    /**
     * Get a fallback world for plugins that need "current world" context.
     * Returns the first loaded world (usually overworld).
     */
    public static World getFallbackWorld() {
        var worlds = Bukkit.getWorlds();
        if (worlds != null && !worlds.isEmpty()) {
            return worlds.get(0);
        }
        return null;
    }

    /**
     * Check if we're on a Folia region thread (not the global tick thread).
     * Some plugins check this to decide whether to use thread-local data.
     */
    public static boolean isRegionThread() {
        String threadName = Thread.currentThread().getName();
        return threadName.contains("Region Scheduler")
                || threadName.contains("region");
    }
}
