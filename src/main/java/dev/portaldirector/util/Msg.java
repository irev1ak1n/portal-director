package dev.portaldirector.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Chat output. Short, consistently coloured, no spam. The look of each kind of message is a MiniMessage template that an admin
 * can change in config.yml under messages. The words themselves are always put in as plain text, so a player name or a typed
 * argument can never add colours, clicks or other tags.
 */
public final class Msg {

    /** Template names and their defaults. These reproduce the original look exactly. */
    public static final Map<String, String> DEFAULTS = Map.of(
            "prefix", "<#b06cff>[PD] </#b06cff>",
            "ok", "<prefix><green><text></green>",
            "info", "<prefix><gray><text></gray>",
            "error", "<prefix><red><text></red>",
            "line", "<gray><text></gray>",
            "head", "<#b06cff>--- <text> ---</#b06cff>",
            "command", "<light_purple><usage></light_purple><gray>  <what></gray>");

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static volatile Map<String, String> templates = DEFAULTS;

    private Msg() {
    }

    /**
     * Uses the given templates for the names it knows. A template that is missing or cannot be parsed falls back to the default,
     * and each problem is reported through {@code problems}.
     */
    public static void configure(Map<String, String> wanted, Consumer<String> problems) {
        Map<String, String> next = new HashMap<>(DEFAULTS);

        for (Map.Entry<String, String> e : wanted.entrySet()) {
            if (!DEFAULTS.containsKey(e.getKey())) {
                problems.accept("messages." + e.getKey() + " is not a message style. The styles are " + String.join(", ", DEFAULTS.keySet().stream().sorted().toList()) + ".");

                continue;
            }

            String why = check(e.getKey(), e.getValue());

            if (why != null) {
                problems.accept("messages." + e.getKey() + " was not used: " + why);

                continue;
            }

            next.put(e.getKey(), e.getValue());
        }

        templates = Map.copyOf(next);
    }

    public static void reset() {
        templates = DEFAULTS;
    }

    /** Why a template cannot be used, or null when it is fine. */
    public static String check(String name, String template) {
        if (template == null || template.isBlank()) return "it is empty.";

        boolean needsText = !name.equals("prefix") && !name.equals("command");
        if (needsText && !template.contains("<text>")) return "it must contain <text>, where the message goes.";
        if (name.equals("command") && (!template.contains("<usage>") || !template.contains("<what>"))) return "it must contain <usage> and <what>.";

        try {
            MINI.deserialize(template, resolvers("x", "x", "x"));
        } catch (RuntimeException e) {
            return "it is not valid MiniMessage (" + e.getMessage() + ").";
        }

        return null;
    }

    private static TagResolver resolvers(String text, String usage, String what) {
        return TagResolver.resolver(
                Placeholder.parsed("prefix", templates.get("prefix")),
                Placeholder.unparsed("text", text),
                Placeholder.unparsed("usage", usage),
                Placeholder.unparsed("what", what));
    }

    private static Component render(String style, String text) {
        return MINI.deserialize(templates.get(style), resolvers(text, "", ""));
    }

    public static void ok(CommandSender to, String text) {
        to.sendMessage(render("ok", text));
    }

    public static void info(CommandSender to, String text) {
        to.sendMessage(render("info", text));
    }

    public static void err(CommandSender to, String text) {
        to.sendMessage(render("error", text));
    }

    public static void line(CommandSender to, String text) {
        to.sendMessage(render("line", text));
    }

    public static void head(CommandSender to, String text) {
        to.sendMessage(render("head", text));
    }

    public static void cmd(CommandSender to, String usage, String what) {
        to.sendMessage(MINI.deserialize(templates.get("command"), resolvers("", usage, what)));
    }
}
