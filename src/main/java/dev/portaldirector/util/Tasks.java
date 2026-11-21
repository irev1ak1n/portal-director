package dev.portaldirector.util;

import org.bukkit.plugin.Plugin;

/** Hands work back to the main thread, but only while the plugin is still running. */
public final class Tasks {

    private Tasks() {
    }

    public static void mainThread(Plugin plugin, Runnable task) {
        if (!plugin.isEnabled()) return;

        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}
