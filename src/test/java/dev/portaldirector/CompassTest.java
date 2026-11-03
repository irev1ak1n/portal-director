package dev.portaldirector;

import dev.portaldirector.util.Compass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompassTest {

    @Test
    void northIsNegativeZAndEastIsPositiveX() {
        assertEquals("north", Compass.direction(0, -10));
        assertEquals("south", Compass.direction(0, 10));
        assertEquals("east", Compass.direction(10, 0));
        assertEquals("west", Compass.direction(-10, 0));
        assertEquals("north-east", Compass.direction(10, -10));
        assertEquals("south-west", Compass.direction(-10, 10));
    }

    @Test
    void describeGivesBlocksAndDirection() {
        assertEquals("18 blocks east", Compass.describe(18, 0));
        assertEquals("right here", Compass.describe(0.5, 0.5));
    }
}
