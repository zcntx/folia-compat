package com.foliacompat.util;

import org.bukkit.Bukkit;

public final class FoliaDetector {

    private static volatile Boolean folia = null;

    private FoliaDetector() {}

    public static boolean isFolia() {
        if (folia != null) return folia;
        synchronized (FoliaDetector.class) {
            if (folia != null) return folia;
            folia = detectFolia();
            return folia;
        }
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean isRegionizedServer() {
        if (!isFolia()) return false;
        try {
            Class<?> serverClass = Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            Object instance = serverClass.getMethod("getCurrentTick").invoke(null);
            return true;
        } catch (Exception e) {
            return true;
        }
    }
}
