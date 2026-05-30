package com.foliacompat;

import com.foliacompat.compat.ScoreboardCompat;
import com.foliacompat.compat.ThreadContextBridge;
import com.foliacompat.entity.CrossRegionEntityBridge;
import com.foliacompat.scheduler.CompatScheduler;
import com.foliacompat.scheduler.CompatSchedulerHolder;
import com.foliacompat.state.GlobalStateStore;
import com.foliacompat.thread.MainThreadProxy;
import com.foliacompat.util.FoliaDetector;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.logging.Logger;

public class FoliaCompatPlugin extends JavaPlugin implements Listener {

    private static FoliaCompatPlugin instance;
    private boolean foliaEnvironment = false;

    // Module toggles
    private boolean enableSchedulerCompat = true;
    private boolean enableThreadSafeState = true;
    private boolean enableEntityBridge = true;
    private boolean enableMainThreadProxy = true;
    private boolean enableScoreboardCompat = true;
    private boolean enableThreadContextBridge = true;

    @Override
    public void onLoad() {
        // Inject CompatScheduler as early as possible (onLoad, before any plugin enables)
        // This ensures Bukkit.getScheduler() returns CompatScheduler for ALL plugins.
        if (FoliaDetector.isFolia()) {
            injectCompatScheduler();
        }
    }

    @Override
    public void onEnable() {
        instance = this;
        Logger logger = getLogger();

        logger.info("========================================");
        logger.info("  FoliaCompat v" + getDescription().getVersion());
        logger.info("  Paper/Folia Plugin Compatibility Layer");
        logger.info("========================================");

        // Detect environment
        foliaEnvironment = FoliaDetector.isFolia();
        if (foliaEnvironment) {
            logger.info("Environment: Folia detected - activating full compatibility layer");
        } else {
            logger.info("Environment: Paper/Spigot detected - compatibility layer in standby mode");
            logger.info("Some features will not be activated on non-Folia servers.");
        }

        // Register event listeners
        Bukkit.getPluginManager().registerEvents(this, this);

        // Install MainThreadProxy
        if (foliaEnvironment && enableMainThreadProxy) {
            MainThreadProxy.install();
        }

        // Load config
        saveDefaultConfig();
        loadConfiguration();

        // Initialize new compat modules
        if (foliaEnvironment) {
            if (enableScoreboardCompat) {
                ScoreboardCompat.initialize();
            }
            if (enableThreadContextBridge) {
                ThreadContextBridge.initialize();
            }
        }

        logger.info("FoliaCompat enabled successfully!");
    }

    @Override
    public void onDisable() {
        getLogger().info("FoliaCompat disabling - cleaning up...");

        // Cancel all registered tasks
        CompatSchedulerHolder.shutdown();

        // Clean up caches and state
        CrossRegionEntityBridge.clearAllCache();
        GlobalStateStore.clearAll();
        ScoreboardCompat.clearCache();

        getLogger().info("FoliaCompat disabled.");
    }

    private void loadConfiguration() {
        try {
            getConfig().options().copyDefaults(true);
            saveConfig();

            enableSchedulerCompat = getConfig().getBoolean("modules.scheduler-compat", true);
            enableThreadSafeState = getConfig().getBoolean("modules.thread-safe-state", true);
            enableEntityBridge = getConfig().getBoolean("modules.entity-bridge", true);
            enableMainThreadProxy = getConfig().getBoolean("modules.main-thread-proxy", true);
            enableScoreboardCompat = getConfig().getBoolean("modules.scoreboard-compat", true);
            enableThreadContextBridge = getConfig().getBoolean("modules.thread-context-bridge", true);

            getLogger().info("Modules:");
            getLogger().info("  Scheduler Compat: " + (enableSchedulerCompat ? "ON" : "OFF"));
            getLogger().info("  Thread-Safe State: " + (enableThreadSafeState ? "ON" : "OFF"));
            getLogger().info("  Entity Bridge: " + (enableEntityBridge ? "ON" : "OFF"));
            getLogger().info("  MainThread Proxy: " + (enableMainThreadProxy ? "ON" : "OFF"));
            getLogger().info("  Scoreboard Compat: " + (enableScoreboardCompat ? "ON" : "OFF"));
            getLogger().info("  Thread Context Bridge: " + (enableThreadContextBridge ? "ON" : "OFF"));
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.WARNING, "Failed to load configuration", e);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up cached entity references when player quits
        CrossRegionEntityBridge.evict(event.getPlayer().getUniqueId());
    }

    /**
     * Inject CompatScheduler into CraftServer by replacing the scheduler field.
     * Uses sun.misc.Unsafe to bypass final modifier restrictions in Java 17+.
     */
    private void injectCompatScheduler() {
        try {
            Object server = Bukkit.getServer();
            Class<?> craftServerClass = server.getClass();

            // Find the scheduler field
            Field schedulerField = null;
            for (Field f : craftServerClass.getDeclaredFields()) {
                if (f.getName().equals("scheduler")) {
                    schedulerField = f;
                    break;
                }
            }

            if (schedulerField == null) {
                getLogger().warning("Could not find CraftServer.scheduler field - scheduler injection skipped");
                return;
            }

            // Use Unsafe to write to a final field (standard reflection can't modify final fields in Java 17+)
            sun.misc.Unsafe unsafe = getUnsafe();
            long offset = unsafe.objectFieldOffset(schedulerField);

            CompatScheduler compatScheduler = (CompatScheduler) CompatSchedulerHolder.getScheduler();
            unsafe.putObject(server, offset, compatScheduler);

            getLogger().info("Successfully injected CompatScheduler into CraftServer!");
            getLogger().info("All Bukkit.getScheduler() calls will now use CompatScheduler.");

        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.WARNING, "Failed to inject CompatScheduler: " + e.getMessage());
        }
    }

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        for (Field f : sun.misc.Unsafe.class.getDeclaredFields()) {
            if (f.getType() == sun.misc.Unsafe.class) {
                f.setAccessible(true);
                return (sun.misc.Unsafe) f.get(null);
            }
        }
        throw new IllegalStateException("Cannot get Unsafe instance");
    }

    public static FoliaCompatPlugin getInstance() {
        return instance;
    }

    public boolean isFoliaEnvironment() {
        return foliaEnvironment;
    }

    /**
     * Check if a module is enabled.
     */
    public boolean isModuleEnabled(String module) {
        return switch (module) {
            case "scheduler-compat" -> enableSchedulerCompat;
            case "thread-safe-state" -> enableThreadSafeState;
            case "entity-bridge" -> enableEntityBridge;
            case "main-thread-proxy" -> enableMainThreadProxy;
            case "scoreboard-compat" -> enableScoreboardCompat;
            case "thread-context-bridge" -> enableThreadContextBridge;
            default -> true;
        };
    }
}
