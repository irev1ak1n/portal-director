package dev.portaldirector;

import dev.portaldirector.preview.PreviewRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PreviewRegistryTest {

    private static PreviewRegistry.Entry entry(String world, int portals) {
        return new PreviewRegistry.Entry(world, List.of(new int[]{1, 2, 3}), portals);
    }

    @Test
    void showingAgainReplacesTheOldPreviewAndReturnsIt() {
        PreviewRegistry registry = new PreviewRegistry();
        UUID admin = UUID.randomUUID();

        assertNull(registry.put(admin, entry("world", 5)));

        PreviewRegistry.Entry old = registry.put(admin, entry("world", 9));

        assertEquals(5, old.portals());
        assertEquals(9, registry.get(admin).portals());
        assertEquals(1, registry.count());
    }

    @Test
    void eachAdminHasTheirOwnPreview() {
        PreviewRegistry registry = new PreviewRegistry();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        registry.put(a, entry("world", 1));
        registry.put(b, entry("world", 2));
        registry.remove(a);

        assertNull(registry.get(a));
        assertEquals(2, registry.get(b).portals());
    }

    @Test
    void removingSomeoneWhoHasNoPreviewIsHarmless() {
        PreviewRegistry registry = new PreviewRegistry();

        assertNull(registry.remove(UUID.randomUUID()));
        assertEquals(0, registry.count());
    }

    @Test
    void viewersAreFoundByWorld() {
        PreviewRegistry registry = new PreviewRegistry();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        registry.put(a, entry("world", 1));
        registry.put(b, entry("world_nether", 1));

        assertEquals(List.of(a), registry.viewersIn("world"));
        assertEquals(List.of(b), registry.viewersIn("world_nether"));
        assertTrue(registry.viewersIn("world_the_end").isEmpty());
    }

    @Test
    void drainReturnsEverythingOnceAndLeavesNothingBehind() {
        PreviewRegistry registry = new PreviewRegistry();
        registry.put(UUID.randomUUID(), entry("world", 1));
        registry.put(UUID.randomUUID(), entry("world", 2));

        assertEquals(2, registry.drain().size());
        assertEquals(0, registry.count());
        assertTrue(registry.drain().isEmpty());
    }

    @Test
    void anEntryCannotBeChangedAfterItIsSaved() {
        List<int[]> positions = new java.util.ArrayList<>(List.of(new int[]{1, 1, 1}));
        PreviewRegistry.Entry e = new PreviewRegistry.Entry("world", positions, 1);

        positions.add(new int[]{2, 2, 2});

        assertEquals(1, e.positions().size());
        assertThrows(UnsupportedOperationException.class, () -> e.positions().add(new int[]{3, 3, 3}));
    }
}
