package dev.portaldirector.tnt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** When each minecart leaves which portal. Pure, so the timing can be tested. */
public final class TntSchedule {

    /** One minecart: spawn {@code tick} ticks after the command, from the portal at {@code portalIndex}. */
    public record Launch(int tick, int portalIndex, int wave) {
    }

    private TntSchedule() {
    }

    public static List<Launch> plan(int portals, int perPortal, int waves, int waveDelayTicks,
                                    int staggerTicks, int intervalTicks) {
        List<Launch> out = new ArrayList<>();
        for (int w = 0; w < waves; w++) {
            for (int p = 0; p < portals; p++) {
                for (int m = 0; m < perPortal; m++) {
                    out.add(new Launch(w * waveDelayTicks + p * staggerTicks + m * intervalTicks, p, w));
                }
            }
        }
        out.sort(Comparator.comparingInt(Launch::tick));
        return out;
    }
}
