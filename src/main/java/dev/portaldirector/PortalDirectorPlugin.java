package dev.portaldirector;

import dev.portaldirector.command.PdCommand;
import dev.portaldirector.entrance.EntranceDirector;
import dev.portaldirector.group.GroupManager;
import dev.portaldirector.group.JoinBlockListener;
import dev.portaldirector.scene.SceneManager;
import dev.portaldirector.scene.SceneStore;
import dev.portaldirector.tnt.TntDirector;
import dev.portaldirector.util.Job;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class PortalDirectorPlugin extends JavaPlugin {

    private final Settings settings = new Settings();
    private SceneManager scenes;
    private GroupManager groups;
    private EntranceDirector entrance;
    private TntDirector tnt;
    private Job activeJob;
    private BukkitTask progress;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings.load(getConfig());
        scenes = new SceneManager(new SceneStore(new File(getDataFolder(), "scenes")), getLogger());
        scenes.loadAll(getConfig().getString("scene.current"));
        groups = new GroupManager(new File(getDataFolder(), "groups.yml"), getLogger(), settings, scenes);
        groups.load();
        entrance = new EntranceDirector(this, settings, scenes);
        tnt = new TntDirector(this, settings, scenes);
        getServer().getPluginManager().registerEvents(new JoinBlockListener(groups, settings), this);
        getServer().getPluginManager().registerEvents(entrance, this);
        getServer().getPluginManager().registerEvents(tnt, this);
        entrance.start();
        tnt.start();
        PdCommand command = new PdCommand(this);
        PluginCommand pd = getCommand("pd");
        if (pd != null) {
            pd.setExecutor(command);
            pd.setTabCompleter(command);
        }
        getLogger().info("PortalDirector ready: " + scenes.all().size() + " scenes, " + groups.all().size() + " groups.");
    }

    @Override
    public void onDisable() {
        if (activeJob != null) activeJob.cancel();
        if (entrance != null) entrance.shutdown();
        if (tnt != null) tnt.shutdown();
        if (scenes != null) scenes.saveAll();
        if (groups != null) groups.save();
        saveSettings();
    }

    public void saveSettings() {
        settings.save(getConfig());
        getConfig().set("scene.current", scenes != null && scenes.current() != null ? scenes.current().name() : null);
        saveConfig();
    }

    public void reload() {
        reloadConfig();
        settings.load(getConfig());
        groups.load();
    }

    public Settings settings() {
        return settings;
    }

    public SceneManager scenes() {
        return scenes;
    }

    public GroupManager groups() {
        return groups;
    }

    public EntranceDirector entrance() {
        return entrance;
    }

    public TntDirector tnt() {
        return tnt;
    }

    public Job activeJob() {
        return activeJob;
    }

    public void activeJob(Job job) {
        this.activeJob = job;
        if (progress != null) {
            progress.cancel();
            progress = null;
        }
        if (job != null) {
            int[] beats = {0};
            progress = getServer().getScheduler().runTaskTimer(this, () -> {
                Job j = activeJob;
                if (j == null) return;
                Component line = Component.text("PortalDirector: " + j.describe() + "  (/pd stop cancels)", NamedTextColor.LIGHT_PURPLE);
                for (Player p : getServer().getOnlinePlayers()) {
                    if (p.hasPermission("portaldirector.admin") || p.hasPermission("portaldirector.generate")) p.sendActionBar(line);
                }
                if (beats[0]++ % 5 == 0) getLogger().info(j.describe());
            }, 10L, 20L);
        }
    }

    /** The error shown when a command needs the plugin to be idle. */
    public IllegalArgumentException busy() {
        String what = activeJob == null ? "something" : activeJob.describe();
        return new IllegalArgumentException("PortalDirector is still working: " + what
                + ". This normally takes a few seconds; try again when it is done, or cancel it with /pd stop.");
    }
}
