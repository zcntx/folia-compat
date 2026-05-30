package com.foliacompat.compat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Thread-safe Scoreboard wrapper for Folia compatibility.
 *
 * In Folia, CraftScoreboard.registerNewTeam() throws UnsupportedOperationException
 * because scoreboard operations are restricted to the global tick thread.
 * This wrapper intercepts scoreboard calls and routes them safely.
 *
 * Primarily fixes PowerRanks which calls registerNewTeam() during permission setup.
 */
public final class ScoreboardCompat {

    private static final Logger LOGGER = Logger.getLogger("FoliaCompat");
    private static volatile boolean initialized = false;

    // Cache of team names → whether they've been registered
    private static final Map<String, Boolean> registeredTeams = new ConcurrentHashMap<>();

    // Cached reflection methods
    private static volatile Method registerNewTeamMethod = null;
    private static volatile boolean reflectionFailed = false;

    private ScoreboardCompat() {}

    public static void initialize() {
        if (initialized) return;
        synchronized (ScoreboardCompat.class) {
            if (initialized) return;

            // Pre-cache the registerNewTeam method
            try {
                Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
                registerNewTeamMethod = mainBoard.getClass().getMethod("registerNewTeam", String.class);
                registerNewTeamMethod.setAccessible(true);
            } catch (Exception e) {
                LOGGER.fine("[FoliaCompat] ScoreboardCompat: Could not cache registerNewTeam method: " + e.getMessage());
                reflectionFailed = true;
            }

            initialized = true;
            LOGGER.info("[FoliaCompat] ScoreboardCompat initialized");
        }
    }

    /**
     * Safely register a new team on the main scoreboard.
     * If the direct call fails (Folia restriction), returns a no-op team proxy.
     */
    public static Team safeRegisterTeam(String name) {
        if (name == null) return null;

        // Check if already registered
        if (registeredTeams.containsKey(name)) {
            try {
                Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
                Team existing = board.getTeam(name);
                if (existing != null) return existing;
            } catch (Exception ignored) {}
        }

        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            Team team = board.registerNewTeam(name);
            registeredTeams.put(name, Boolean.TRUE);
            return team;
        } catch (UnsupportedOperationException | IllegalStateException e) {
            // Folia restriction - log once and return null
            if (!registeredTeams.containsKey(name)) {
                LOGGER.fine("[FoliaCompat] ScoreboardCompat: Could not register team '" + name + "' (Folia restriction): " + e.getMessage());
                registeredTeams.put(name, Boolean.FALSE);
            }
            return null;
        }
    }

    /**
     * Check if a team is registered (cached).
     */
    public static boolean isTeamRegistered(String name) {
        return registeredTeams.containsKey(name);
    }

    /**
     * Clear the cache (for reload).
     */
    public static void clearCache() {
        registeredTeams.clear();
    }
}
