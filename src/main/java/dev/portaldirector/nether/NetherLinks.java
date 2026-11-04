package dev.portaldirector.nether;

import dev.portaldirector.gen.PortalEdits;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.BlockLog;
import dev.portaldirector.scene.RemovalJob;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.scene.SceneStore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Nether portal for every PortalDirector portal, created on first use and remembered across restarts.
 *
 * <p>Why not vanilla linking: Minecraft sends a player through an Overworld portal to the Nether position at one eighth
 * of the coordinates and reuses any Nether portal within 16 blocks of it. Portals that are 8 to 100 blocks apart in the
 * Overworld land within a few blocks of each other in the Nether, so they all share one or two Nether portals, and coming
 * back searches 128 blocks around eight times the coordinates and picks whichever Overworld portal is nearest. Here each
 * pair is stored, and {@link PortalTravelListener} points the vanilla teleport at exactly the partner.
 *
 * <p>The Nether-side blocks are logged like a scene, so removing a portal restores them, and only them.
 */
public final class NetherLinks {

    /** How far from the aimed spot a safe place is looked for. */
    public static final int SEARCH_RADIUS = 32;

    private final Plugin plugin;
    private final SceneStore store;
    private final File pairsFile;
    private final Scene nether;
    /** "scene/portal id" to the id of the partner in {@link #nether}. */
    private final Map<String, Integer> pairs = new LinkedHashMap<>();

    public NetherLinks(Plugin plugin, File dir) {
        this.plugin = plugin;
        this.store = new SceneStore(dir);
        this.pairsFile = new File(dir, "pairs.yml");
        Scene loaded = null;
        try {
            if (store.exists("links")) loaded = store.load("links");
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Could not load the Nether portal pairs: " + e.getMessage());
        }
        this.nether = loaded != null ? loaded : new Scene("links", "");
        if (pairsFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(pairsFile);
            var section = y.getConfigurationSection("pairs");
            if (section != null) {
                for (String k : section.getKeys(false)) pairs.put(k.replace('|', '/'), section.getInt(k));
            }
        }
    }

    private static String key(String scene, int id) {
        return scene + "/" + id;
    }

    public int count() {
        return pairs.size();
    }

    public Scene netherScene() {
        return nether;
    }

    public void save() {
        try {
            YamlConfiguration y = new YamlConfiguration();
            for (Map.Entry<String, Integer> e : pairs.entrySet()) y.set("pairs." + e.getKey().replace('/', '|'), e.getValue());
            if (pairs.isEmpty()) y.createSection("pairs");
            y.save(pairsFile);
            store.save(nether);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save the Nether portal pairs: " + e.getMessage());
        }
    }

    /** The Nether world that belongs to an Overworld, or null when there is none. */
    public static World netherWorld(World overworld) {
        World named = Bukkit.getWorld(overworld.getName() + "_nether");
        if (named != null && named.getEnvironment() == World.Environment.NETHER) return named;
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.NETHER) return w;
        }
        return null;
    }

    /** The Nether partner of a portal, if one has been created. */
    public PortalSpec partnerOf(String sceneName, int id) {
        Integer nid = pairs.get(key(sceneName, id));
        return nid == null ? null : nether.portals().get(nid);
    }

    /** The Overworld side of a Nether portal: {scene name, portal id}, or null when the player is not in one of ours. */
    public Object[] originOf(BoundingBox body, String netherWorldName) {
        if (!netherWorldName.equals(nether.worldName())) return null;
        for (PortalSpec ns : nether.portals().values()) {
            if (!ns.interiorBox().overlaps(body)) continue;
            for (Map.Entry<String, Integer> e : pairs.entrySet()) {
                if (e.getValue() == ns.id()) {
                    int slash = e.getKey().lastIndexOf('/');
                    return new Object[]{e.getKey().substring(0, slash), Integer.parseInt(e.getKey().substring(slash + 1))};
                }
            }
        }
        return null;
    }

    /**
     * The partner of an Overworld portal, created if this is the first trip. Builds on a safe spot near one eighth of the
     * coordinates, never close to another pair or to anything but natural Nether terrain.
     *
     * @throws IllegalStateException with a plain explanation when no safe place exists
     */
    public PortalSpec ensure(World overworld, Scene scene, PortalSpec p) {
        World nw = netherWorld(overworld);
        if (nw == null) throw new IllegalStateException("This server has no Nether world to link to.");
        String k = key(scene.name(), p.id());
        Integer nid = pairs.get(k);
        if (nid != null) {
            PortalSpec existing = nether.portals().get(nid);
            if (existing != null) {
                if (!intact(nw, existing)) build(nw, existing);
                return existing;
            }
            pairs.remove(k);
        }
        int cx = (int) Math.floor(p.centerX());
        int cz = (int) Math.floor(p.centerZ());
        int tx = Math.floorDiv(cx, 8);
        int tz = Math.floorDiv(cz, 8);
        int reach = SEARCH_RADIUS + 6;
        for (int x = (tx - reach) >> 4; x <= (tx + reach) >> 4; x++) {
            for (int z = (tz - reach) >> 4; z <= (tz + reach) >> 4; z++) nw.getChunkAt(x, z);
        }
        int preferred = Math.max(40, Math.min(100, p.y()));
        PortalSpec found = NetherSite.find(new BukkitNetherGrid(nw), nether.nextId(), tx, tz, preferred, p.axis(), p.facing(),
                new ArrayList<>(nether.portals().values()), SEARCH_RADIUS);
        if (found == null) {
            throw new IllegalStateException("There is no safe room for a Nether portal within " + SEARCH_RADIUS + " blocks of " + tx + ", " + tz
                    + " (the Nether spot for " + scene.nameOf(p.id()) + "). Nothing was built, and the portal was not linked to another one.");
        }
        nether.worldName(nw.getName());
        build(nw, found);
        nether.addPortal(found);
        pairs.put(k, found.id());
        save();
        plugin.getLogger().info("Paired " + scene.nameOf(p.id()) + " with a new Nether portal at " + found.anchorX() + " " + found.anchorY() + " " + found.anchorZ() + ".");
        return found;
    }

    private void build(World nw, PortalSpec spec) {
        for (PortalEdits.Edit e : PortalEdits.forPortal(nw, spec, 0)) PortalEdits.apply(nw, nether.blocks(), e);
    }

    private boolean intact(World nw, PortalSpec spec) {
        if (!nw.isChunkLoaded(spec.x() >> 4, spec.z() >> 4)) nw.getChunkAt(spec.x() >> 4, spec.z() >> 4);
        for (int[] c : spec.interiorBlocks()) {
            if (nw.getBlockAt(c[0], c[1], c[2]).getType() != Material.NETHER_PORTAL) return false;
        }
        return true;
    }

    /** The partner of one portal is taken down and its blocks restored; nothing else in the Nether is touched. */
    public void unlink(String sceneName, int id) {
        Integer nid = pairs.remove(key(sceneName, id));
        if (nid == null) return;
        removeNetherPortal(nid);
        save();
    }

    public void unlinkScene(String sceneName) {
        List<String> mine = new ArrayList<>();
        for (String k : pairs.keySet()) {
            if (k.substring(0, k.lastIndexOf('/')).equals(sceneName)) mine.add(k);
        }
        for (String k : mine) removeNetherPortal(pairs.remove(k));
        if (!mine.isEmpty()) save();
    }

    /** Nether portals that no pair points to (a removal that was interrupted) are taken down. */
    public void cleanOrphans() {
        List<Integer> orphans = new ArrayList<>();
        for (int id : nether.portals().keySet()) {
            if (!pairs.containsValue(id)) orphans.add(id);
        }
        for (int id : orphans) removeNetherPortal(id);
    }

    private void removeNetherPortal(int nid) {
        PortalSpec ns = nether.portals().get(nid);
        if (ns == null) return;
        World nw = Bukkit.getWorld(nether.worldName());
        if (nw == null) {
            plugin.getLogger().warning("The Nether world " + nether.worldName() + " is not loaded; its portal " + nid + " stays until it is.");
            return;
        }
        List<BlockLog.Rec> mine = new ArrayList<>();
        for (BlockLog.Rec r : nether.blocks().all()) {
            if (ns.contains(r.pos().x(), r.pos().y(), r.pos().z())) mine.add(r);
        }
        new RemovalJob(plugin, nw, nether, 400, mine, result -> {
            nether.dropPortal(nid);
            save();
        }).start();
    }
}
