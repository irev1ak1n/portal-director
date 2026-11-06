package dev.portaldirector.sequence;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The settings of every sequence, kept in their own file (sequences.yml). They never touch the global /pd set values, and
 * only what someone changed is stored, so a default that improves in an update still reaches everyone who never changed it.
 */
public final class SeqSettings {

    private final File file;
    private final Logger log;
    private final Map<String, Map<String, String>> changed = new LinkedHashMap<>();

    public SeqSettings(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public void load() {
        changed.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String seq : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(seq);
            if (s == null) continue;
            Map<String, String> m = new LinkedHashMap<>();
            for (String k : s.getKeys(false)) m.put(k, String.valueOf(s.get(k)));
            changed.put(seq, m);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Map<String, String>> e : changed.entrySet()) {
            for (Map.Entry<String, String> o : e.getValue().entrySet()) y.set(e.getKey() + "." + o.getKey(), o.getValue());
        }
        try {
            file.getParentFile().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            log.severe("Could not save the sequence settings: " + ex.getMessage());
        }
    }

    public String get(String sequence, SeqOption option) {
        Map<String, String> m = changed.get(sequence);
        String v = m == null ? null : m.get(option.key());
        if (v == null) return option.def();
        try {
            return option.normalize(v);
        } catch (IllegalArgumentException e) {
            log.warning("Ignoring the saved " + sequence + " " + option.key() + " '" + v + "': " + e.getMessage());
            return option.def();
        }
    }

    public boolean isChanged(String sequence, SeqOption option) {
        Map<String, String> m = changed.get(sequence);
        return m != null && m.containsKey(option.key());
    }

    /** Stores an already checked value. */
    public void set(String sequence, SeqOption option, String value) {
        if (value.equals(option.def())) {
            Map<String, String> m = changed.get(sequence);
            if (m != null) {
                m.remove(option.key());
                if (m.isEmpty()) changed.remove(sequence);
            }
        } else {
            changed.computeIfAbsent(sequence, k -> new LinkedHashMap<>()).put(option.key(), value);
        }
        save();
    }

    public void reset(String sequence) {
        changed.remove(sequence);
        save();
    }
}
