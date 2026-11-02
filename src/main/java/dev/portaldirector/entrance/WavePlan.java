package dev.portaldirector.entrance;

import java.util.ArrayList;
import java.util.List;

/** Splits portal slots into release waves. One wave means everybody emerges on the same tick. */
public final class WavePlan {

    /** Slots {@code from} (inclusive) to {@code to} (exclusive) are released {@code tick} ticks after the command. */
    public record Wave(int tick, int from, int to) {
    }

    private WavePlan() {
    }

    public static List<Wave> synchronous(int slots) {
        List<Wave> out = new ArrayList<>();
        if (slots > 0) out.add(new Wave(0, 0, slots));
        return out;
    }

    public static List<Wave> staggered(int slots, int waveSize, int delayTicks) {
        List<Wave> out = new ArrayList<>();
        int size = Math.max(1, waveSize);
        for (int from = 0, i = 0; from < slots; from += size, i++) {
            out.add(new Wave(i * delayTicks, from, Math.min(slots, from + size)));
        }
        return out;
    }
}
