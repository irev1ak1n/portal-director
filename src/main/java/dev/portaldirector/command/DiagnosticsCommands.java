package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.diagnostics.Diagnostics;
import dev.portaldirector.util.Msg;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** /pd diagnostics [export|reset]: what is running, what is loaded and how long the plugin's work takes. */
final class DiagnosticsCommands {

    private final PortalDirectorPlugin pl;

    DiagnosticsCommands(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    Sub diagnostics() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                String what = a.isEmpty() ? "show" : a.get(0).toLowerCase(Locale.ROOT);
                Diagnostics diagnostics = new Diagnostics(pl);

                switch (what) {
                    case "show" -> {
                        Msg.head(s, "Diagnostics");

                        for (String line : diagnostics.lines()) Msg.line(s, line.startsWith("  ") ? line : "- " + line);

                        Msg.line(s, "/pd diagnostics export saves this to a file without player names; /pd diagnostics reset clears the plugin timings.");
                    }
                    case "export" -> {
                        try {
                            var file = diagnostics.export();
                            Msg.ok(s, "Saved " + file.getFileName() + " in the plugin folder. It has versions, counts and timings only: no player names, ids, addresses or paths.");
                        } catch (IOException e) {
                            throw new IllegalArgumentException("Could not save the report: " + e.getMessage());
                        }
                    }
                    case "reset" -> {
                        pl.metrics().reset();
                        Msg.ok(s, "The plugin timings and counters start again from zero.");
                    }
                    default -> throw new IllegalArgumentException("Use /pd diagnostics, /pd diagnostics export or /pd diagnostics reset.");
                }
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                return a.size() == 1 ? List.of("export", "reset") : List.of();
            }
        };
    }
}
