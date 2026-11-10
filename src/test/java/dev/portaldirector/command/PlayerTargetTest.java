package dev.portaldirector.command;

import dev.portaldirector.util.PlayerTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A player's name as a place: what counts as a name, how it is found, and how /pd create takes "around". */
class PlayerTargetTest {

    private ServerMock server;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void namesAreToldApartFromHereAndCoordinates() {
        assertTrue(PlayerTarget.looksLikeName("Steve"));
        assertTrue(PlayerTarget.looksLikeName("Dream_2"));
        assertTrue(PlayerTarget.looksLikeName("12abc"), "a mix of digits and letters is a name, so a typo gets a clear message");
        assertFalse(PlayerTarget.looksLikeName("here"));
        assertFalse(PlayerTarget.looksLikeName("HERE"));
        assertFalse(PlayerTarget.looksLikeName("150"));
        assertFalse(PlayerTarget.looksLikeName("-200"));
        assertFalse(PlayerTarget.looksLikeName("12.5"));
        assertFalse(PlayerTarget.looksLikeName("~"));
        assertFalse(PlayerTarget.looksLikeName("~-3"));
        assertFalse(PlayerTarget.looksLikeName(""));
        assertFalse(PlayerTarget.looksLikeName(null));
    }

    @Test
    void anOnlinePlayerIsFoundWhateverTheCapitalisation() {
        var steve = server.addPlayer("Steve");
        server.addPlayer("Alex");
        assertSame(steve, PlayerTarget.online("Steve"));
        assertSame(steve, PlayerTarget.online("steve"));
        assertSame(steve, PlayerTarget.online("STEVE"));
        assertEquals(List.of("Alex", "Steve"), PlayerTarget.names());
    }

    @Test
    void anUnknownPlayerIsRefusedAndTheOnlineOnesAreNamed() {
        server.addPlayer("Steve");
        String msg = assertThrows(IllegalArgumentException.class, () -> PlayerTarget.online("Nobody")).getMessage();
        assertTrue(msg.contains("no online player called Nobody"), msg);
        assertTrue(msg.contains("Steve"), msg);
        assertTrue(msg.contains("here, x y z"), msg);
    }

    @Test
    void createTakesAroundAndAPlayerAnywhereAmongTheOtherOptions() {
        var a = GenerateCommands.CreateArgs.parse(List.of("30", "around", "Steve", "more"));
        assertEquals(30, a.count());
        assertEquals("Steve", a.around());
        assertTrue(a.more() && !a.replacement() && !a.skipBlocked());
        var b = GenerateCommands.CreateArgs.parse(List.of("around", "Dream", "replacement", "skip-blocked"));
        assertNull(b.count());
        assertEquals("Dream", b.around());
        assertTrue(b.replacement() && b.skipBlocked());
        assertNull(GenerateCommands.CreateArgs.parse(List.of("30")).around(), "no around means around you, as before");
        assertNull(GenerateCommands.CreateArgs.parse(List.of()).around());
    }

    @Test
    void aroundNeedsAPlayerAndCanOnlyBeGivenOnce() {
        String missing = assertThrows(IllegalArgumentException.class, () -> GenerateCommands.CreateArgs.parse(List.of("30", "around"))).getMessage();
        assertTrue(missing.contains("Around whom"), missing);
        assertThrows(IllegalArgumentException.class, () -> GenerateCommands.CreateArgs.parse(List.of("around", "A", "around", "B")));
    }

    @Test
    void theOldOptionsAndTheirRefusalsAreUnchanged() {
        assertEquals(List.of("more", "replacement", "skip-blocked"), GenerateCommands.CREATE_OPTIONS);
        assertThrows(IllegalArgumentException.class, () -> GenerateCommands.CreateArgs.parse(List.of("more", "replacement")));
        assertThrows(IllegalArgumentException.class, () -> GenerateCommands.CreateArgs.parse(List.of("30", "40")));
        assertThrows(IllegalArgumentException.class, () -> GenerateCommands.CreateArgs.parse(List.of("portal")));
    }
}
