package dev.portaldirector.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A player used as a place: wherever they are at the moment a command runs. Used by /pd create portal, /pd create ... around
 * and the sequences, next to "here" and x y z. The position is read once, when the command is processed, so a player who is
 * running or flying is not followed afterwards.
 */
public final class PlayerTarget {

    private static final Pattern COORDINATE = Pattern.compile("~(-?\\d+)?|-?\\d+(\\.\\d+)?");

    private PlayerTarget() {
    }

    /** True for text that can be a player's name: not "here", and not a number or a ~ coordinate. */
    public static boolean looksLikeName(String text) {
        return text != null && !text.isEmpty() && !text.equalsIgnoreCase("here") && !COORDINATE.matcher(text).matches();
    }

    /** The online player with this name (not case sensitive), or an error that says what to do instead. */
    public static Player online(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.getName().equalsIgnoreCase(name)) return o;
        }
        throw new IllegalArgumentException("There is no online player called " + name + ". Use here, x y z, or the name of a player who is online"
                + (Bukkit.getOnlinePlayers().isEmpty() ? " (nobody is online right now)." : ": " + String.join(", ", names()) + "."));
    }

    /** Names of the players who are online, A to Z. */
    public static List<String> names() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /** What a message says about where a player was found. */
    public static String describe(Player p) {
        var l = p.getLocation();
        return p.getName() + " at " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    /** Lower case, for comparisons. */
    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
