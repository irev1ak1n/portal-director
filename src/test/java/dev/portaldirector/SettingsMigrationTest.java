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
    void defaultsAreSensible() {
        Settings s = new Settings();
        assertEquals(8, s.gen.spacingH);
        assertEquals(5, s.gen.spacingV);
    }
}
