package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.util.Msg;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** /pd preview: show, hide and status of a private, fake-block preview of random portals. */
final class PreviewCommands {

    private static final List<String> VERBS = List.of("hide", "show", "status");

    private final PortalDirectorPlugin pl;

    PreviewCommands(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    Sub preview() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                if (!(s instanceof Player admin)) throw new IllegalArgumentException("The preview is shown on a player's screen, so it needs a player to run it.");

                if (a.isEmpty()) {
                    overview(s);

                    return;
                }

                switch (a.get(0).toLowerCase(Locale.ROOT)) {
                    case "show" -> show(admin, a);
                    case "hide" -> pl.preview().hide(admin, true);
                    case "status" -> pl.preview().status(admin);
                    default -> throw new IllegalArgumentException("'" + a.get(0) + "' is not something the preview does. Use /pd preview show, hide or status.");
                }
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                return a.size() == 1 ? VERBS : List.of();
            }
        };
    }

    private void show(Player admin, List<String> a) {
        int x = admin.getLocation().getBlockX();
        int z = admin.getLocation().getBlockZ();

        if (a.size() == 3) {
            try {
                x = Integer.parseInt(a.get(1));
                z = Integer.parseInt(a.get(2));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Use whole numbers: /pd preview show [x z]");
            }
        } else if (a.size() != 1) {
            throw new IllegalArgumentException("Use /pd preview show to centre on yourself, or /pd preview show <x> <z>.");
        }

        pl.preview().show(admin, x, z);
    }

    private void overview(CommandSender s) {
        Msg.head(s, "Preview");
        Msg.cmd(s, "/pd preview show [x z]", "show where random portals would go, only to you");
        Msg.cmd(s, "/pd preview hide", "take the preview off your screen");
        Msg.cmd(s, "/pd preview status", "what you are looking at");
    }
}
