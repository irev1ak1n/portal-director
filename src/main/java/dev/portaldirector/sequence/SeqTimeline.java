package dev.portaldirector.sequence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The schedule of a sequence, worked out before it starts: when each portal appears, when each player appears and when
 * each minecart leaves. Every gap is a fresh random draw from its range, so nothing happens in lockstep. A player or a
 * minecart is never scheduled before its portal.
 */
public final class SeqTimeline {

    public enum Kind { PORTAL, PLAYER, LAUNCH }

    /** {@code index} is the portal (PORTAL, LAUNCH) or the player (PLAYER). */
    public record Event(int tick, Kind kind, int index) {
    }

    /** A range of ticks, both ends included. */
    public record Range(int min, int max) {
        public Range {
            if (min < 1) min = 1;
            if (max < min) max = min;
        }

        public static Range seconds(double lo, double hi) {
            return new Range(ticks(lo), ticks(hi));
        }
    }

    private SeqTimeline() {
    }

    public static int ticks(double seconds) {
        return Math.max(1, (int) Math.round(seconds * 20.0));
    }

    private static int draw(RandomGenerator rnd, Range r) {
        return r.min() == r.max() ? r.min() : r.min() + rnd.nextInt(r.max() - r.min() + 1);
    }

    /** The first portal appears at tick 0, each next one a random gap later. */
    public static int[] portalTicks(int portals, Range gap, RandomGenerator rnd) {
        int[] out = new int[portals];
        int t = 0;
        for (int i = 0; i < portals; i++) {
            out[i] = t;
            t += draw(rnd, gap);
        }
        return out;
    }

    private static void sort(List<Event> events) {
        events.sort(Comparator.comparingInt(Event::tick).thenComparingInt(e -> e.kind().ordinal()).thenComparingInt(Event::index));
    }

    /** Portals, and every player a random delay after their own portal. */
    public static List<Event> sky(int[] portalTick, int[] playerPortal, Range playerDelay, RandomGenerator rnd) {
        List<Event> out = new ArrayList<>();
        for (int i = 0; i < portalTick.length; i++) out.add(new Event(portalTick[i], Kind.PORTAL, i));
        for (int p = 0; p < playerPortal.length; p++) {
            out.add(new Event(portalTick[playerPortal[p]] + draw(rnd, playerDelay), Kind.PLAYER, p));
        }
        sort(out);
        return out;
    }

    /**
     * Portals, and each portal's minecarts: the first a random delay after the portal appears, every next one a random gap
     * after the one before.
     */
    public static List<Event> tnt(int[] portalTick, int[] minecarts, Range delay, RandomGenerator rnd) {
        List<Event> out = new ArrayList<>();
        for (int i = 0; i < portalTick.length; i++) {
            out.add(new Event(portalTick[i], Kind.PORTAL, i));
            int t = portalTick[i];
            for (int k = 0; k < minecarts[i]; k++) {
                t += draw(rnd, delay);
                out.add(new Event(t, Kind.LAUNCH, i));
            }
        }
        sort(out);
        return out;
    }

    /** A whole number from {@code lo} to {@code hi}, both included. */
    public static int between(RandomGenerator rnd, int lo, int hi) {
        return hi <= lo ? lo : lo + rnd.nextInt(hi - lo + 1);
    }
}
