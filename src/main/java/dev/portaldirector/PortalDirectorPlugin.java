package dev.portaldirector;

import dev.portaldirector.command.PdCommand;
import dev.portaldirector.group.Cinematic;
import dev.portaldirector.group.GroupManager;
import dev.portaldirector.group.JoinBlockListener;
import dev.portaldirector.nether.NetherLinks;
import dev.portaldirector.sequence.SequenceManager;
import dev.portaldirector.nether.PortalTravelListener;
import dev.portaldirector.db.Database;
import dev.portaldirector.db.Journal;
import dev.portaldirector.take.TakeService;
import dev.portaldirector.scene.SceneManager;
import dev.portaldirector.scene.SceneStore;
import dev.portaldirector.scene.SceneWriter;
import dev.portaldirector.util.Metrics;
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
    private final Metrics metrics = new Metrics();
    private SceneWriter sceneWriter;
    private Database database;
    private Journal journal;
    private TakeService takes;
    private SceneManager scenes;
    private GroupManager groups;
    private TntDirector tnt;
    private Cinematic cinematic;
    private NetherLinks netherLinks;
    private SequenceManager sequences;
    private Job activeJob;
    private BukkitTask progress;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings.load(getConfig());
        SceneStore sceneStore = new SceneStore(new File(getDataFolder(), "scenes"));
        sceneWriter = getConfig().getBoolean("performance.async-saves", true) ? new SceneWriter(sceneStore, getLogger(), metrics) : null;
        scenes = new SceneManager(sceneStore, getLogger(), sceneWriter, metrics);
        database = getConfig().getBoolean("database.enabled", true)
                ? Database.open(new File(getDataFolder(), "portaldirector.db").toPath(), getLogger(), metrics)
                : Database.disabled(getLogger(), metrics, "turned off in config.yml");
        journal = new Journal(database, getLogger());
        journal.recover();
        takes = new TakeService(this);
        scenes.loadAll(getConfig().getString("scene.current"));
        groups = new GroupManager(new File(getDataFolder(), "groups.yml"), getLogger());
        groups.load();
        groups.migrateLegacy(scenes);
        cinematic = new Cinematic(this);
        netherLinks = new NetherLinks(this, new File(getDataFolder(), "nether"));
        tnt = new TntDirector(this, settings, scenes);
        getServer().getPluginManager().registerEvents(new JoinBlockListener(groups), this);
        getServer().getPluginManager().registerEvents(tnt, this);
        getServer().getPluginManager().registerEvents(cinematic, this);
        getServer().getPluginManager().registerEvents(new PortalTravelListener(this), this);
        netherLinks.cleanOrphans();
        sequences = new SequenceManager(this);
        getServer().getPluginManager().registerEvents(sequences, this);
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
        if (takes != null) takes.shutdown();
        if (sequences != null) sequences.shutdown();
        if (tnt != null) tnt.shutdown();
        if (cinematic != null) cinematic.shutdown();
        if (netherLinks != null) netherLinks.save();
        if (scenes != null) scenes.saveAll();
        if (sceneWriter != null) sceneWriter.close();
        if (database != null) database.close();
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
        groups.migrateLegacy(scenes);
    }

    public Database database() {
        return database;
    }

    public TakeService takes() {
        return takes;
    }

    public Journal journal() {
        return journal;
    }

    public SceneWriter sceneWriter() {
        return sceneWriter;
    }

    public Metrics metrics() {
        return metrics;
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

    public Cinematic cinematic() {
        return cinematic;
    }

    public NetherLinks netherLinks() {
        return netherLinks;
    }

    public SequenceManager sequences() {
        return sequences;
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
