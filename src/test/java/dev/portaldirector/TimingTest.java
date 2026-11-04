package dev.portaldirector;

import dev.portaldirector.tnt.TntSchedule;
import dev.portaldirector.util.Durations;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimingTest {

    @Test
    void tntLaunchesEverythingAtOnceWithoutStagger() {
        List<TntSchedule.Launch> plan = TntSchedule.plan(30, 1, 1, 100, 0, 4);
        assertEquals(30, plan.size());
        assertTrue(plan.stream().allMatch(l -> l.tick() == 0));
    }

    @Test
    void tntWavesAreSpacedByTheDelay() {
        List<TntSchedule.Launch> plan = TntSchedule.plan(10, 2, 5, 400, 0, 4);
        assertEquals(100, plan.size());
        assertEquals(0, plan.get(0).tick());
        assertEquals(1600 + 4, plan.get(plan.size() - 1).tick());
        assertEquals(5, plan.stream().mapToInt(TntSchedule.Launch::wave).max().getAsInt() + 1);
    }

    @Test
    void tntStaggerOffsetsPortalsInsideAWave() {
        List<TntSchedule.Launch> plan = TntSchedule.plan(4, 1, 1, 100, 10, 4);
        assertEquals(List.of(0, 10, 20, 30), plan.stream().map(TntSchedule.Launch::tick).toList());
    }

    @Test
    void durationsAcceptSecondsTicksAndMinutes() {
        assertEquals(200, Durations.parseTicks("10"));
        assertEquals(30, Durations.parseTicks("1.5s"));
        assertEquals(20, Durations.parseTicks("20t"));
        assertEquals(2400, Durations.parseTicks("2m"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parseTicks("soon"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parseTicks("-5"));
    }
}
