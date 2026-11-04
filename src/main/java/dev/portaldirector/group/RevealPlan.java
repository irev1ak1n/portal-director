package dev.portaldirector.group;

import java.util.ArrayList;
import java.util.List;

/**
 * When prepared players become visible. They appear in small groups instead of one by one: 3, 2, 4, 2, 3 and so on, the
 * first group at once and the others one delay apart, with every third gap half as long again. The pattern is fixed, so a
 * scene plays the same way every time, but it never ticks like a metronome.
 */
public final class RevealPlan {

    /** One group of players: those from index {@code from} up to {@code to} (exclusive) appear {@code tick} ticks after the start. */
    public record Batch(int tick, int from, int to) {
    }

    private static final int[] SIZES = {3, 2, 4, 2, 3};
    private static final double[] GAPS = {1.0, 1.0, 1.5};

    private RevealPlan() {
    }

    public static List<Batch> plan(int players, int delayTicks) {
        List<Batch> out = new ArrayList<>();
        int delay = Math.max(1, delayTicks);
        int index = 0;
        int tick = 0;
        for (int k = 0; index < players; k++) {
            int size = Math.min(SIZES[k % SIZES.length], players - index);
            out.add(new Batch(tick, index, index + size));
            index += size;
            tick += Math.max(1, (int) Math.round(delay * GAPS[k % GAPS.length]));
        }
        return out;
    }
}
