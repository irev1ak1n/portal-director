package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.take.TakeService;
import dev.portaldirector.util.Msg;
import org.bukkit.command.CommandSender;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /pd take: save, list, info, prepare, stage, start, reset and delete recording setups. */
final class TakeCommands {

    private static final List<String> VERBS = List.of("arena", "save", "list", "info", "prepare", "stage", "start", "reset", "delete");

    private final PortalDirectorPlugin pl;

    TakeCommands(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    Sub take() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                if (a.isEmpty()) {
                    overview(s);

                    return;
                }

                String verb = a.get(0).toLowerCase(Locale.ROOT);
                TakeService svc = pl.takes();

                try {
                    switch (verb) {
                        case "arena" -> arena(s, a);
                        case "list" -> svc.list(s);
                        case "save" -> save(s, a);
                        case "info" -> svc.info(s, name(a, "info"));
                        case "prepare" -> svc.prepare(s, name(a, "prepare"));
                        case "stage" -> svc.stage(s, name(a, "stage"));
                        case "start" -> svc.start(s, name(a, "start"));
                        case "reset" -> svc.reset(s, name(a, "reset"));
                        case "delete" -> svc.delete(s, name(a, "delete"));
                        default -> throw new IllegalArgumentException("'" + a.get(0) + "' is not something takes do. Use /pd take " + String.join(", ", VERBS) + ".");
                    }
                } catch (SQLException e) {
                    throw new IllegalArgumentException("The database failed: " + e.getMessage());
                }
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() == 1) return VERBS;

                String verb = a.get(0).toLowerCase(Locale.ROOT);

                if (verb.equals("arena")) {
                    if (a.size() == 2) return List.of("clear", "info", "restore", "save", "set");
                    if (a.size() == 3) return names();
                    if (a.size() == 4 && a.get(1).equalsIgnoreCase("restore")) return List.of("confirm");

                    return List.of();
                }
                if (a.size() == 2 && !verb.equals("list") && !verb.equals("save")) return names();
                if (verb.equals("save") && a.size() == 3) return List.of("sequence", "positions");
                if (verb.equals("save") && a.size() >= 3 && a.get(a.size() - 2).equalsIgnoreCase("sequence")) {
                    return pl.sequences().all().stream().map(q -> q.name()).toList();
                }
                if (verb.equals("save") && a.size() == 5) return List.of("positions");

                return List.of();
            }
        };
    }

    private List<String> names() {
        try {
            return new ArrayList<>(pl.takes().names().get(2, java.util.concurrent.TimeUnit.SECONDS));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String name(List<String> a, String verb) {
        if (a.size() < 2) throw new IllegalArgumentException("Which take? /pd take " + verb + " <name>. /pd take list shows them.");

        return a.get(1);
    }

    private void arena(CommandSender s, List<String> a) throws SQLException {
        var arena = pl.arenas();

        if (a.size() < 3) {
            throw new IllegalArgumentException("Use /pd take arena set <take> <x1 y1 z1 x2 y2 z2> | save <take> | restore <take> confirm | info <take> | clear <take>");
        }

        String verb = a.get(1).toLowerCase(Locale.ROOT);
        String take = a.get(2);

        switch (verb) {
            case "set" -> {
                if (a.size() != 9) throw new IllegalArgumentException("Give two corners: /pd take arena set " + take + " <x1 y1 z1 x2 y2 z2>");

                int[] c = new int[6];

                try {
                    for (int i = 0; i < 6; i++) c[i] = Integer.parseInt(a.get(3 + i));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("The corners are whole numbers: /pd take arena set " + take + " <x1 y1 z1 x2 y2 z2>");
                }

                arena.define(s, take, c);
            }
            case "save" -> arena.capture(s, take);
            case "restore" -> arena.restore(s, take, a.size() > 3 && a.get(3).equalsIgnoreCase("confirm"));
            case "info" -> arena.info(s, take);
            case "clear" -> arena.clear(s, take);
            default -> throw new IllegalArgumentException("'" + a.get(1) + "' is not something arenas do. Use set, save, restore, info or clear.");
        }
    }

    private void save(CommandSender s, List<String> a) throws SQLException {
        if (a.size() < 2) throw new IllegalArgumentException("Name the take: /pd take save <name> [sequence <name>] [positions]");

        String sequence = null;
        boolean positions = false;

        for (int i = 2; i < a.size(); i++) {
            String word = a.get(i).toLowerCase(Locale.ROOT);

            if (word.equals("positions")) {
                positions = true;
            } else if (word.equals("sequence") && i + 1 < a.size()) {
                sequence = a.get(++i);
            } else {
                throw new IllegalArgumentException("'" + a.get(i) + "' is not part of a take. Use: /pd take save <name> [sequence <name>] [positions]");
            }
        }

        pl.takes().save(s, a.get(1), sequence, positions);
    }

    private void overview(CommandSender s) {
        Msg.head(s, "Takes");
        Msg.cmd(s, "/pd take save <name> [sequence <name>] [positions]", "write the current setup down; nothing starts or moves");
        Msg.cmd(s, "/pd take prepare <name>", "check it and put settings and assignments back; nobody moves");
        Msg.cmd(s, "/pd take stage <name>", "move players to their saved positions");
        Msg.cmd(s, "/pd take start <name>", "run the sequence of a prepared take");
        Msg.cmd(s, "/pd take reset <name>", "stop it and clear what it built");
        Msg.line(s, "More: /pd take list, /pd take info <name>, /pd take delete <name>");
    }
}
