package dev.portaldirector.preview;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who is looking at a preview, and of what. It only remembers; sending and restoring blocks is done by the manager. Keeping this
 * apart makes the cleanup rules easy to test.
 */
public final class PreviewRegistry {

    /** One admin's preview: the world it is in and every block position that was changed on their screen. */
    public record Entry(String world, List<int[]> positions, int portals) {

        public Entry {
            positions = List.copyOf(positions);
        }
    }

    private final Map<UUID, Entry> shown = new HashMap<>();

    /** Remembers a preview and returns the one it replaced, or null. */
    public Entry put(UUID admin, Entry entry) {
        return shown.put(admin, entry);
    }

    public Entry get(UUID admin) {
        return shown.get(admin);
    }

    public Entry remove(UUID admin) {
        return shown.remove(admin);
    }

    public int count() {
        return shown.size();
    }

    /** Everyone who is looking at a preview in this world. */
    public List<UUID> viewersIn(String world) {
        List<UUID> out = new ArrayList<>();

        for (Map.Entry<UUID, Entry> e : shown.entrySet()) {
            if (e.getValue().world().equals(world)) out.add(e.getKey());
        }

        return out;
    }

    /** Forgets everything and returns what was there, so the caller can put the real blocks back. */
    public Map<UUID, Entry> drain() {
        Map<UUID, Entry> all = new HashMap<>(shown);
        shown.clear();

        return all;
    }
}
