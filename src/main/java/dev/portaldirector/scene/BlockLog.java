package dev.portaldirector.scene;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every block a scene changed: what was there before and what the scene put there.
 * On removal a block is only restored while it still holds the scene's block.
 */
public final class BlockLog {

    public record Pos(int x, int y, int z) {
    }

    public record Rec(Pos pos, String original, String placed) {
    }

    private final Map<Pos, Rec> records = new LinkedHashMap<>();

    public void record(int x, int y, int z, String original, String placed) {
        Pos pos = new Pos(x, y, z);
        Rec old = records.get(pos);
        records.put(pos, new Rec(pos, old != null ? old.original() : original, placed));
    }

    public Collection<Rec> all() {
        return records.values();
    }

    public int size() {
        return records.size();
    }

    public void remove(Pos pos) {
        records.remove(pos);
    }

    public boolean has(int x, int y, int z) {
        return records.containsKey(new Pos(x, y, z));
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    public void write(DataOutput out) throws IOException {
        Map<String, Integer> palette = new LinkedHashMap<>();
        for (Rec r : records.values()) {
            palette.putIfAbsent(r.original(), palette.size());
            palette.putIfAbsent(r.placed(), palette.size());
        }
        out.writeInt(palette.size());
        for (String s : palette.keySet()) out.writeUTF(s);
        out.writeInt(records.size());
        for (Rec r : records.values()) {
            out.writeInt(r.pos().x());
            out.writeInt(r.pos().y());
            out.writeInt(r.pos().z());
            out.writeInt(palette.get(r.original()));
            out.writeInt(palette.get(r.placed()));
        }
    }

    public static BlockLog read(DataInput in) throws IOException {
        BlockLog log = new BlockLog();
        int paletteSize = in.readInt();
        List<String> palette = new ArrayList<>(paletteSize);
        for (int i = 0; i < paletteSize; i++) palette.add(in.readUTF());
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            int x = in.readInt();
            int y = in.readInt();
            int z = in.readInt();
            log.record(x, y, z, palette.get(in.readInt()), palette.get(in.readInt()));
        }
        return log;
    }
}
