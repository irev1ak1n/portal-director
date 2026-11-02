package dev.portaldirector.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.CommandSender;

/** Chat output. Short, consistently coloured, no spam. */
public final class Msg {

    private static final TextColor PREFIX = TextColor.color(0xB06CFF);

    private Msg() {
    }

    private static Component prefix() {
        return Component.text("[PD] ", PREFIX);
    }

    public static void ok(CommandSender to, String text) {
        to.sendMessage(prefix().append(Component.text(text, NamedTextColor.GREEN)));
    }

    public static void info(CommandSender to, String text) {
        to.sendMessage(prefix().append(Component.text(text, NamedTextColor.GRAY)));
    }

    public static void err(CommandSender to, String text) {
        to.sendMessage(prefix().append(Component.text(text, NamedTextColor.RED)));
    }

    public static void line(CommandSender to, String text) {
        to.sendMessage(Component.text(text, NamedTextColor.GRAY));
    }

    public static void head(CommandSender to, String text) {
        to.sendMessage(Component.text("--- " + text + " ---", PREFIX));
    }

    public static void cmd(CommandSender to, String usage, String what) {
        to.sendMessage(Component.text(usage, NamedTextColor.LIGHT_PURPLE).append(Component.text("  " + what, NamedTextColor.GRAY)));
    }
}
