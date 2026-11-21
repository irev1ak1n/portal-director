package dev.portaldirector.arena;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import dev.portaldirector.db.Take;
import org.bukkit.World;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Uses the WorldEdit API, which FastAsyncWorldEdit also provides. This class is only loaded when one of them is installed; the
 * rest of the plugin never refers to it directly.
 */
public final class WorldEditBackend implements ArenaBackend {

    private final String name;

    public WorldEditBackend(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    private static CuboidRegion region(com.sk89q.worldedit.world.World world, Take.Arena a) {
        return new CuboidRegion(world, BlockVector3.at(a.minX(), a.minY(), a.minZ()), BlockVector3.at(a.maxX(), a.maxY(), a.maxZ()));
    }

    private static ClipboardFormat format() {
        ClipboardFormat format = ClipboardFormats.findByAlias("schem");
        if (format == null) throw new IllegalStateException("This WorldEdit has no schematic format called schem.");

        return format;
    }

    @Override
    public void capture(World world, Take.Arena arena, Path file) throws Exception {
        var weWorld = BukkitAdapter.adapt(world);
        CuboidRegion region = region(weWorld, arena);
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);

        try (EditSession session = WorldEdit.getInstance().newEditSession(weWorld)) {
            ForwardExtentCopy copy = new ForwardExtentCopy(session, region, clipboard, region.getMinimumPoint());
            copy.setCopyingEntities(false);
            Operations.complete(copy);
        }

        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");

        try (OutputStream out = Files.newOutputStream(temp); ClipboardWriter writer = format().getWriter(out)) {
            writer.write(clipboard);
        }

        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void restore(World world, Take.Arena arena, Path file) throws Exception {
        if (!Files.isRegularFile(file)) throw new IllegalStateException("The saved arena file is missing: " + file.getFileName());

        var weWorld = BukkitAdapter.adapt(world);
        CuboidRegion region = region(weWorld, arena);
        Clipboard clipboard;

        try (InputStream in = Files.newInputStream(file); ClipboardReader reader = format().getReader(in)) {
            clipboard = reader.read();
        }

        try (EditSession session = WorldEdit.getInstance().newEditSession(weWorld)) {
            Operation paste = new ClipboardHolder(clipboard).createPaste(session)
                    .to(region.getMinimumPoint())
                    .ignoreAirBlocks(false)
                    .copyEntities(false)
                    .build();

            Operations.complete(paste);
        }
    }
}
