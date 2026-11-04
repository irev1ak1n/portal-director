package dev.portaldirector.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * /pd help: short pages that fit the chat window. Every command line puts an example into the chat box when clicked,
 * and each page has Previous / Next controls. Commands are listed A to Z inside a topic.
 */
final class HelpCommand implements Sub {

    private static final TextColor HEADING = TextColor.color(0xB06CFF);
    private static final int PER_PAGE = 6;

    /** One command: what is shown, what a click puts in the chat box, and what it does. */
    record Entry(String command, String insert, String what) {
    }

    /** A help topic. {@code compact} entries take one line instead of two. */
    record Topic(String key, String title, List<String> aliases, List<String> intro, List<Entry> entries, List<String> outro, boolean compact) {
    }

    /** One screen of help. */
    record Page(Topic topic, List<String> intro, List<Entry> entries, List<String> outro) {
    }

    private static Entry e(String command, String insert, String what) {
        return new Entry(command, insert, what);
    }

    static final List<Topic> TOPICS = List.of(
            new Topic("start", "Start here", List.of("overview", "main"),
                    List.of("Build and remove portals in five steps:"),
                    List.of(
                            e("1. /pd set", "/pd set ", "choose layout, size and spacing"),
                            e("2. /pd create", "/pd create", "build portals with those settings"),
                            e("3. /pd list", "/pd list", "see every portal and where it is"),
                            e("4. /pd remove", "/pd remove ", "take one portal down by name"),
                            e("5. /pd clear", "/pd clear", "remove all and restore the ground")),
                    List.of("Try it (click a line, then press Enter):", "/pd set layout grid", "/pd create", "Topics: create, portals, set, layouts, reveal, group, tnt, scenes"),
                    true),
            new Topic("create", "Creating portals", List.of("generate", "build"),
                    List.of("Builds the layout chosen with /pd set layout."),
                    sorted(List.of(
                            e("/pd create", "/pd create", "build the selected layout around you"),
                            e("/pd create 30", "/pd create 30", "random layout: how many portals"),
                            e("/pd create more", "/pd create more", "keep the portals you have and build another set"),
                            e("/pd create replacement", "/pd create replacement", "remove the old portals first, then build"),
                            e("/pd create skip-blocked", "/pd create skip-blocked", "build only the portals that fit, skip blocked spots"))),
                    List.of("Combine them: /pd create 30 more skip-blocked", "Stand on the ground or in the air: /pd set mode"),
                    false),
            new Topic("portals", "Single named portals", List.of("manual", "portal"),
                    List.of("Name one portal yourself and place it exactly."),
                    sorted(List.of(
                            e("/pd create portal <name> here <direction>", "/pd create portal EntranceA here north", "build it where you stand"),
                            e("/pd create portal <name> <x> <y> <z> <direction>", "/pd create portal EntranceB 150 100 -200 east", "build it at exact coordinates"),
                            e("/pd list", "/pd list", "names and coordinates of every portal"),
                            e("/pd remove <name>", "/pd remove EntranceA", "take that portal down, not case sensitive"),
                            e("/pd remove <x> <y> <z>", "/pd remove 150 100 -200", "remove the portal anchored exactly there"))),
                    List.of("Direction: north, south, east or west.", "x y z is the lower block inside the doorway."),
                    false),
            new Topic("set", "Settings", List.of("settings", "config"),
                    List.of("All configuration goes through /pd set."),
                    sorted(List.of(
                            e("/pd set center here", "/pd set center here", "build around this spot (or x y z, or reset)"),
                            e("/pd set count 30", "/pd set count 30", "random layout: number of portals"),
                            e("/pd set facing south", "/pd set facing south", "north, south, east, west or random"),
                            e("/pd set layout grid", "/pd set layout grid", "random, grid, square or wave"),
                            e("/pd set mode air", "/pd set mode air", "ground, air or mixed: where portals stand"),
                            e("/pd set radius 100", "/pd set radius 100", "random layout: how far from the center"),
                            e("/pd set spacing horizontal 8", "/pd set spacing horizontal 8", "gap between portals side by side (or vertical)"),
                            e("/pd set staging here", "/pd set staging here", "hold prepared players here (or clear)"),
                            e("/pd settings", "/pd settings", "show every value; /pd settings all lists more"))),
                    List.of("Layout sizes: /pd help layouts. Reveal: /pd help reveal."),
                    false),
            new Topic("layouts", "Layouts", List.of("layout", "formations"),
                    List.of("Pick with /pd set layout, set its size, then /pd create."),
                    sorted(List.of(
                            e("/pd set layout grid", "/pd set grid rows 4", "a wall: grid rows 4 and grid columns 5 = 20"),
                            e("/pd set layout random", "/pd set layout random", "scattered: count and radius"),
                            e("/pd set layout square", "/pd set square per-side 5", "a ring: square per-side 5, layers 3 = 60"),
                            e("/pd set layout wave", "/pd set wave rows 4", "wavy rows: wave rows 4 and wave per-row 5"))),
                    List.of("Rows and layers go up; columns and sides go across.", "Squares face inward or outward."),
                    false),
            new Topic("reveal", "Cinematic reveal", List.of("delay", "cinematic", "generation"),
                    List.of("Make portals appear one by one instead of at once."),
                    sorted(List.of(
                            e("/pd set generation delay 0.5", "/pd set generation delay 0.5", "seconds between portals (0.05 to 60)"),
                            e("/pd set generation effects on", "/pd set generation effects on", "soft sound and particles per portal"),
                            e("/pd set generation instant", "/pd set generation instant", "everything at once (the default)"),
                            e("/pd set generation delayed", "/pd set generation delayed", "one portal at a time"),
                            e("/pd set generation order <order>", "/pd set generation order left-to-right", "see the orders below"),
                            e("/pd stop", "/pd stop", "cancel; portals already built stay"))),
                    List.of("Orders: bottom-to-top, clockwise, counterclockwise,", "left-to-right, random, top-to-bottom, auto."),
                    false),
            new Topic("group", "Groups and entrances", List.of("players", "groups", "entrance"),
                    List.of("Players walk out of their own portal together."),
                    sorted(List.of(
                            e("/pd group add <player>", "/pd group add ", "add a player by hand (or remove)"),
                            e("/pd group assign <group> auto", "/pd group assign team auto", "portal 1 to player 1, and so on"),
                            e("/pd group assign <group> <portal> <player>", "/pd group assign team EntranceA ", "one player to one named portal"),
                            e("/pd group assignments", "/pd group assignments", "who is at which portal"),
                            e("/pd group block set <block>", "/pd group block set emerald_block", "stepping on this block joins the group"),
                            e("/pd group create <group>", "/pd group create team", "make a group"),
                            e("/pd group disband <group>", "/pd group disband ", "delete a group"),
                            e("/pd group info", "/pd group info", "members and their portals"),
                            e("/pd group prepare <group>", "/pd group prepare team", "everyone waits inside their portal"),
                            e("/pd group release <group>", "/pd group release team", "everyone walks out together"),
                            e("/pd group reset <group>", "/pd group reset team", "cancel and send everyone back"))),
                    List.of("Patterns: all-at-once, clockwise, opposite-sides,", "side-by-side (squares), staggered <secs> [per wave]."),
                    false),
            new Topic("tnt", "TNT minecarts", List.of("minecart", "minecarts"),
                    List.of("Minecarts leave through the portals."),
                    sorted(List.of(
                            e("/pd tnt clear", "/pd tnt clear", "stop and remove every TNT minecart"),
                            e("/pd tnt fuse 3", "/pd tnt fuse 3", "explode this many seconds after launch (or off)"),
                            e("/pd tnt launch all", "/pd tnt launch all", "one per portal; also floating, ground, group <name>"),
                            e("/pd tnt launch <name>", "/pd tnt launch EntranceA", "or named portals, e.g. EntranceA,EntranceB"),
                            e("/pd tnt limit 300", "/pd tnt limit 300", "most minecarts alive at once"),
                            e("/pd tnt mode safe", "/pd tnt mode safe", "safe: no explosions; real: they explode"),
                            e("/pd tnt speed 1.3", "/pd tnt speed 1.3", "launch speed"),
                            e("/pd tnt status", "/pd tnt status", "what is on, how many, which mode"),
                            e("/pd tnt stop", "/pd tnt stop", "stop launching; minecarts out keep going"),
                            e("/pd tnt terrain-damage on", "/pd tnt terrain-damage on", "explosions break blocks (default off)"),
                            e("/pd tnt wave 5 20", "/pd tnt wave 5 20", "5 launches, 20 seconds apart"))),
                    List.of(),
                    false),
            new Topic("scenes", "Scenes and removal", List.of("scene", "remove", "clear"),
                    List.of("It remembers every portal and block it changed,", "even after a restart, so it can put the ground back."),
                    sorted(List.of(
                            e("/pd clear", "/pd clear", "remove all portals and restore the ground"),
                            e("/pd list", "/pd list", "names and coordinates (/pd list 2 for page 2)"),
                            e("/pd reload", "/pd reload", "reload config.yml"),
                            e("/pd remove <name>", "/pd remove portal-1", "take one portal down"),
                            e("/pd remove <x> <y> <z>", "/pd remove 150 100 -200", "the same, by its anchor coordinates"),
                            e("/pd stop", "/pd stop", "cancel a build or removal in progress"))),
                    List.of("Blocks you changed yourself since are left alone."),
                    false));

    private static List<Entry> sorted(List<Entry> in) {
        List<Entry> out = new ArrayList<>(in);
        out.sort(Comparator.comparing((Entry x) -> x.command().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Every page in order: the topics one after another, long topics split. */
    static final List<Page> PAGES = buildPages();

    private static List<Page> buildPages() {
        List<Page> out = new ArrayList<>();
        for (Topic t : TOPICS) {
            int n = t.entries().size();
            int chunks = Math.max(1, (n + PER_PAGE - 1) / PER_PAGE);
            for (int i = 0; i < chunks; i++) {
                List<Entry> part = t.entries().subList(i * PER_PAGE, Math.min(n, (i + 1) * PER_PAGE));
                out.add(new Page(t, i == 0 ? t.intro() : List.of(), part, i == chunks - 1 ? t.outro() : List.of()));
            }
        }
        return out;
    }

    /** Topic names and aliases accepted by /pd help, A to Z. */
    static List<String> topicNames() {
        List<String> out = new ArrayList<>();
        for (Topic t : TOPICS) {
            if (!t.key().equals("start")) out.add(t.key());
        }
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    static int pageOfTopic(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        for (int i = 0; i < PAGES.size(); i++) {
            Topic t = PAGES.get(i).topic();
            if (t.key().equals(w) || t.aliases().contains(w)) return i;
        }
        return -1;
    }

    @Override
    public void run(CommandSender s, List<String> a) {
        int index = 0;
        if (!a.isEmpty()) {
            String w = a.get(0);
            if (w.matches("\\d{1,4}")) {
                int n = Integer.parseInt(w);
                if (n < 1 || n > PAGES.size()) throw new IllegalArgumentException("Pages go from 1 to " + PAGES.size() + ". /pd help 1 is the start.");
                index = n - 1;
            } else {
                index = pageOfTopic(w);
                if (index < 0) throw new IllegalArgumentException("No help topic called " + w + ". Topics: " + String.join(", ", topicNames()) + ", or a page from 1 to " + PAGES.size() + ".");
            }
        }
        for (Component line : render(index)) s.sendMessage(line);
    }

    @Override
    public List<String> tab(CommandSender s, List<String> a) {
        if (a.size() != 1) return List.of();
        List<String> out = new ArrayList<>(topicNames());
        for (int i = 1; i <= PAGES.size(); i++) out.add(String.valueOf(i));
        return out;
    }

    private static Component click(String text, TextColor color, ClickEvent click, String hover) {
        return Component.text(text, color).clickEvent(click).hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    /** The lines of one page, header and footer included. */
    static List<Component> render(int index) {
        Page p = PAGES.get(index);
        List<Component> out = new ArrayList<>();
        out.add(Component.text("PortalDirector", HEADING, TextDecoration.BOLD).append(Component.text("  " + p.topic().title(), NamedTextColor.WHITE)));
        for (String line : p.intro()) out.add(Component.text(line, NamedTextColor.GRAY));
        for (Entry en : p.entries()) {
            Component cmd = click(en.command(), NamedTextColor.AQUA, ClickEvent.suggestCommand(en.insert()), "Click to put " + en.insert().trim() + " in your chat box");
            if (p.topic().compact()) {
                out.add(cmd.append(Component.text("  " + en.what(), NamedTextColor.GRAY)));
            } else {
                out.add(cmd);
                out.add(Component.text("  " + en.what(), NamedTextColor.GRAY));
            }
        }
        for (String line : p.outro()) {
            if (line.startsWith("/pd ")) {
                out.add(click(line, NamedTextColor.AQUA, ClickEvent.suggestCommand(line), "Click to put this in your chat box"));
            } else if (line.startsWith("Topics: ")) {
                out.add(topicLinks());
            } else {
                out.add(Component.text(line, NamedTextColor.GRAY));
            }
        }
        out.add(footer(index));
        return out;
    }

    private static Component topicLinks() {
        Component c = Component.text("Topics: ", NamedTextColor.GRAY);
        List<String> names = topicNames();
        for (int i = 0; i < names.size(); i++) {
            c = c.append(click(names.get(i), NamedTextColor.YELLOW, ClickEvent.runCommand("/pd help " + names.get(i)), "Open /pd help " + names.get(i)));
            if (i < names.size() - 1) c = c.append(Component.text(" ", NamedTextColor.GRAY));
        }
        return c;
    }

    private static Component footer(int index) {
        int total = PAGES.size();
        Component prev = index > 0
                ? click("<< Previous", NamedTextColor.YELLOW, ClickEvent.runCommand("/pd help " + index), "Page " + index)
                : Component.text("<< Previous", NamedTextColor.DARK_GRAY);
        Component next = index < total - 1
                ? click("Next >>", NamedTextColor.YELLOW, ClickEvent.runCommand("/pd help " + (index + 2)), "Page " + (index + 2))
                : Component.text("Next >>", NamedTextColor.DARK_GRAY);
        return prev.append(Component.text("   Page " + (index + 1) + "/" + total + "   ", NamedTextColor.WHITE)).append(next)
                .append(Component.text("   /pd help <page>", NamedTextColor.DARK_GRAY));
    }
}
