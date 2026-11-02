package dev.portaldirector.command;

import org.bukkit.command.CommandSender;

import java.util.List;

/** One /pd subcommand. User mistakes are reported by throwing {@link IllegalArgumentException}. */
interface Sub {

    void run(CommandSender sender, List<String> args);

    default List<String> tab(CommandSender sender, List<String> args) {
        return List.of();
    }
}
