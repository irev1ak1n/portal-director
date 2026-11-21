package dev.portaldirector.arena;

import dev.portaldirector.db.Take;
import org.bukkit.World;

import java.nio.file.Path;

/** Saves and puts back a box of blocks. The only real implementation needs WorldEdit or FastAsyncWorldEdit. */
public interface ArenaBackend {

    /** Shown to admins, for example "FastAsyncWorldEdit 2.16.2". */
    String name();

    /** Writes the blocks of the box to the file. Must be called on the main thread. */
    void capture(World world, Take.Arena arena, Path file) throws Exception;

    /** Puts the saved blocks back in the same place. Must be called on the main thread. */
    void restore(World world, Take.Arena arena, Path file) throws Exception;
}
