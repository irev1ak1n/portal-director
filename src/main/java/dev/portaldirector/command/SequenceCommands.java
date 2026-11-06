package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.group.Group;
import dev.portaldirector.sequence.SeqOption;
import dev.portaldirector.sequence.Sequence;
import dev.portaldirector.util.Msg;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /pd sequence: list, status, stop, and per sequence info, set, run and reset. */
final class SequenceCommands {

    private final PortalDirectorPlugin pl;

    SequenceCommands(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    Sub sequence() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                if (a.isEmpty()) {
                    overview(s);
                    return;
                }
                String first = a.get(0).toLowerCase(Locale.ROOT);
                switch (first) {
                    case "list" -> list(s);
                    case "status" -> pl.sequences().status(s);
                    case "stop" -> pl.sequences().stop(s);
                    default -> {
                        Sequence seq = pl.sequences().get(first);
                        if (seq == null) {
                            throw new IllegalArgumentException("There is no sequence called '" + a.get(0) + "'. The sequences are: " + names() + ". /pd sequence list describes them.");
                        }
                        one(s, seq, a.subList(1, a.size()));
                    }
                }
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() == 1) {
                    List<String> out = new ArrayList<>(List.of("list", "status", "stop"));
                    pl.sequences().all().forEach(q -> out.add(q.name()));
                    return out;
                }
                Sequence seq = pl.sequences().get(a.get(0));
                if (seq == null) return List.of();
                if (a.size() == 2) return List.of("info", "reset", "run", "set");
                String verb = a.get(1).toLowerCase(Locale.ROOT);
                if (verb.equals("run")) return seq.runTab(s, a.subList(2, a.size()));
                if (verb.equals("set")) {
                    if (a.size() == 3) return seq.options().stream().map(SeqOption::key).toList();
                    if (a.size() == 4) {
                        SeqOption o = seq.option(a.get(2));
                        if (o == null) return List.of();
                        if (o.key().equals("group")) return pl.groups().all().stream().map(Group::name).toList();
                        return o.suggestions();
                    }
                }
                return List.of();
            }
        };
    }

    private String names() {
        return String.join(", ", pl.sequences().all().stream().map(Sequence::name).toList());
    }

    private void overview(CommandSender s) {
        Msg.head(s, "Sequences");
        for (Sequence q : pl.sequences().all()) Msg.cmd(s, "/pd sequence " + q.name() + " run ...", q.summary());
        Msg.line(s, "More: /pd sequence list, /pd sequence <name> info, /pd help sequence");
    }

    private void list(CommandSender s) {
        Msg.head(s, "Sequences");
        for (Sequence q : pl.sequences().all()) {
            Msg.line(s, "  " + q.name() + "  (" + q.title() + ")");
            Msg.line(s, "    " + q.summary());
            Msg.line(s, "    Try: " + q.example());
        }
        Msg.line(s, "  /pd sequence <name> info shows the details and settings. /pd sequence status and /pd sequence stop control a running one.");
    }

    private void one(CommandSender s, Sequence q, List<String> a) {
        if (a.isEmpty()) {
            throw new IllegalArgumentException("What should " + q.name() + " do? /pd sequence " + q.name() + " info | run " + q.usage().replaceFirst("^run ", "") + " | set <option> <value> | reset");
        }
        String verb = a.get(0).toLowerCase(Locale.ROOT);
        List<String> rest = a.subList(1, a.size());
        switch (verb) {
            case "info" -> info(s, q);
            case "run" -> q.run(s, rest);
            case "reset" -> {
                q.reset();
                Msg.ok(s, q.title() + " is back to its default settings. (/pd set and the other sequences were not touched.)");
            }
            case "set" -> {
                if (rest.size() < 2) {
                    throw new IllegalArgumentException("Which setting, and to what? /pd sequence " + q.name() + " set <option> <value>. Settings: "
                            + String.join(", ", q.options().stream().map(SeqOption::key).toList()));
                }
                String value = String.join(" ", rest.subList(1, rest.size()));
                String stored = q.set(rest.get(0), value);
                SeqOption o = q.option(rest.get(0));
                Msg.ok(s, q.title() + ": " + o.key() + " is now " + (stored.isEmpty() ? "(empty)" : stored) + (stored.equals(o.def()) ? " (the default)" : " (default " + (o.def().isEmpty() ? "empty" : o.def()) + ")") + ". Only this sequence changed.");
            }
            default -> throw new IllegalArgumentException("'" + a.get(0) + "' is not something a sequence does. Use /pd sequence " + q.name() + " info, run, set or reset.");
        }
    }

    private void info(CommandSender s, Sequence q) {
        Msg.head(s, q.title() + " (" + q.name() + ")");
        Msg.line(s, "  " + q.summary());
        Msg.line(s, "  Run it: /pd sequence " + q.name() + " " + q.usage());
        Msg.line(s, "  Example: " + q.example());
        for (String f : q.facts()) Msg.line(s, "  - " + f);
        Msg.line(s, "  Settings (/pd sequence " + q.name() + " set <option> <value>):");
        for (SeqOption o : q.options()) {
            String v = q.value(o.key());
            String shown = v.isEmpty() ? (o.key().equals("group") ? groupShown() : "(empty)") : v;
            Msg.line(s, String.format("    %-17s %-14s %s", o.key(), shown, o.hint()));
        }
        Msg.line(s, "  /pd sequence " + q.name() + " reset restores the defaults. These settings are separate from /pd set.");
    }

    private String groupShown() {
        Group only = pl.groups().only();
        return only == null ? "(none chosen)" : "(" + only.name() + ")";
    }
}
