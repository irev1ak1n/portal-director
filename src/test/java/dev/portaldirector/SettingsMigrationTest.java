package dev.portaldirector;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsMigrationTest {

    @Test
    void anOldSingleSpacingBecomesTheHorizontalSpacing() {
        YamlConfiguration old = new YamlConfiguration();
        old.set("generate.spacing", 12);
        old.set("generate.radius", 70);
        Settings s = new Settings();
        s.load(old);
        assertEquals(12, s.gen.spacingH);
        assertEquals(5, s.gen.spacingV);
        assertEquals(70, s.gen.radius);
        YamlConfiguration saved = new YamlConfiguration();
        s.save(saved);
        assertFalse(saved.contains("generate.spacing"));
        assertEquals(12, saved.getInt("generate.spacing-horizontal"));
        assertEquals(5, saved.getInt("generate.spacing-vertical"));
    }

    @Test
    void newKeysWinOverTheOldOne() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("generate.spacing", 12);
        c.set("generate.spacing-horizontal", 20);
        c.set("generate.spacing-vertical", 9);
        Settings s = new Settings();
        s.load(c);
        assertEquals(20, s.gen.spacingH);
        assertEquals(9, s.gen.spacingV);
    }

    @Test
    void theOldHeightSettingsMoveToTheNewNamesWithTheirValues() {
        YamlConfiguration old = new YamlConfiguration();
        old.set("generate.height-min", 22);
        old.set("generate.height-max", 44);
        old.set("generate.min-y", 10);
        old.set("generate.max-y", 200);
        Settings s = new Settings();
        s.load(old);
        assertEquals(22, s.gen.altitudeMin);
        assertEquals(44, s.gen.altitudeMax);
        assertEquals(10, s.gen.worldYMin);
        assertEquals(200, s.gen.worldYMax);
        YamlConfiguration saved = old;
        s.save(saved);
        for (String gone : new String[]{"height-min", "height-max", "min-y", "max-y"}) assertFalse(saved.contains("generate." + gone), gone);
        assertEquals(22, saved.getInt("generate.altitude-min"));
        assertEquals(44, saved.getInt("generate.altitude-max"));
        assertEquals(10, saved.getInt("generate.world-y-min"));
        assertEquals(200, saved.getInt("generate.world-y-max"));
    }

    @Test
    void newHeightKeysWinOverOldOnes() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("generate.height-min", 22);
        c.set("generate.altitude-min", 30);
        Settings s = new Settings();
        s.load(c);
        assertEquals(30, s.gen.altitudeMin);
    }

    @Test
    void removedSettingsAreDroppedFromTheConfigFile() {
        YamlConfiguration old = new YamlConfiguration();
        old.set("generate.air-ratio", 0.2);
        old.set("generate.orientation", "x");
        old.set("generate.max-slope", 5);
        old.set("entrance.walk-ticks", 40);
        old.set("entrance.staging-location.world", "world");
        old.set("groups.auto-assign", false);
        old.set("groups.announce-join", false);
        old.set("generate.radius", 77);
        Settings s = new Settings();
        s.load(old);
        s.save(old);
        for (String key : new String[]{"generate.air-ratio", "generate.orientation", "generate.max-slope", "entrance", "groups"}) {
            assertFalse(old.contains(key), key);
        }
        assertEquals(77, s.gen.radius);
        assertNull(s.options().get("generate.air-ratio"));
        assertNull(s.options().get("generate.orientation"));
        assertNull(s.options().get("generate.max-slope"));
        assertTrue(s.options().keySet().stream().noneMatch(k -> k.startsWith("entrance.") || k.startsWith("groups.")));
    }

    @Test
    void anOldAutoFacingBecomesAFacingTheLayoutTakes() {
        YamlConfiguration old = new YamlConfiguration();
        old.set("generate.facing", "auto");
        Settings s = new Settings();
        s.load(old);
        assertEquals(dev.portaldirector.gen.FacingMode.RANDOM, s.gen.facing);
        YamlConfiguration square = new YamlConfiguration();
        square.set("generate.layout", "square");
        square.set("generate.facing", "auto");
        Settings q = new Settings();
        q.load(square);
        assertEquals(dev.portaldirector.gen.FacingMode.INWARD, q.gen.facing);
        YamlConfiguration inwardRandom = new YamlConfiguration();
        inwardRandom.set("generate.facing", "inward");
        Settings r = new Settings();
        r.load(inwardRandom);
        assertEquals(dev.portaldirector.gen.FacingMode.RANDOM, r.gen.facing);
    }

    @Test
    void minimumsCannotPassMaximumsAndTheMessageSaysWhatToDo() {
        Settings s = new Settings();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> s.options().get("generate.altitude-min").set("70"));
        assertTrue(e.getMessage().contains("altitude-max"), e.getMessage());
        assertEquals(15, s.gen.altitudeMin, "a refused value changes nothing");
        s.options().get("generate.altitude-max").set("100");
        s.options().get("generate.altitude-min").set("70");
        assertEquals(70, s.gen.altitudeMin);
        assertThrows(IllegalArgumentException.class, () -> s.options().get("generate.altitude-max").set("50"));
        assertThrows(IllegalArgumentException.class, () -> s.options().get("generate.world-y-min").set("400"));
        assertThrows(IllegalArgumentException.class, () -> s.options().get("generate.world-y-max").set("-100"));
        s.options().get("generate.world-y-min").set("80");
        s.options().get("generate.world-y-max").set("180");
        assertEquals(80, s.gen.worldYMin);
        assertEquals(180, s.gen.worldYMax);
    }

    @Test
    void reversedLimitsInAnEditedConfigAreRepairedOnLoad() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("generate.altitude-min", 80);
        c.set("generate.altitude-max", 20);
        c.set("generate.world-y-min", 200);
        c.set("generate.world-y-max", 100);
        Settings s = new Settings();
        s.load(c);
        assertEquals(20, s.gen.altitudeMin);
        assertEquals(80, s.gen.altitudeMax);
        assertEquals(100, s.gen.worldYMin);
        assertEquals(200, s.gen.worldYMax);
    }

    @Test
    void facingMustSuitTheLayout() {
        Settings s = new Settings();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> s.options().get("generate.facing").set("inward"));
        assertTrue(e.getMessage().contains("square"), e.getMessage());
        s.options().get("generate.facing").set("north");
        s.options().get("generate.layout").set("square");
        IllegalArgumentException f = assertThrows(IllegalArgumentException.class, () -> s.options().get("generate.facing").set("north"));
        assertTrue(f.getMessage().contains("inward"), f.getMessage());
        s.options().get("generate.facing").set("outward");
        assertEquals(dev.portaldirector.gen.FacingMode.OUTWARD, s.gen.facing);
    }

    @Test
    void problemsAreFoundBeforeBuilding() {
        dev.portaldirector.gen.GenSettings g = new dev.portaldirector.gen.GenSettings();
        assertNull(g.problem());
        g.worldYMin = 100;
        g.worldYMax = 103;
        assertTrue(g.problem().contains("only 4 blocks tall"), g.problem());
        g.worldYMax = 104;
        assertNull(g.problem());
        g.layout = dev.portaldirector.gen.Layout.SQUARE;
        assertTrue(g.problem().contains("inward"), g.problem());
    }

    @Test
    void thereIsNoRadiusMarginSettingAndAnOldOneIsDropped() {
        Settings s = new Settings();
        assertNull(s.options().get("generate.radius-margin"));
        s.options().get("generate.radius").set("100");
        assertEquals(105, s.gen.effectiveRadius());
        YamlConfiguration c = new YamlConfiguration();
        c.set("generate.radius-margin", 20);
        c.set("generate.radius", 100);
        s.load(c);
        s.save(c);
        assertFalse(c.contains("generate.radius-margin"));
        assertEquals(105, s.gen.effectiveRadius(), "the margin stays fixed whatever an old file said");
    }

    @Test
    void defaultsAreSensible() {
        Settings s = new Settings();
        assertEquals(8, s.gen.spacingH);
        assertEquals(5, s.gen.spacingV);
        assertEquals(15, s.gen.altitudeMin);
        assertEquals(60, s.gen.altitudeMax);
        assertEquals(-64, s.gen.worldYMin);
        assertEquals(320, s.gen.worldYMax);
    }
}
