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
                    List.of("Try it (click a line, then press Enter):", "/pd set layout grid", "/pd create", "Topics: create, examples, group, layouts, portals, reveal, scenes, set, tnt"),
                    true),
            new Topic("create", "Creating portals", List.of("generate", "build"),
                    List.of("Builds the layout chosen with /pd set layout."),
                    sorted(List.of(
                            e("/pd create", "/pd create", "build the selected layout around you"),
                            e("/pd create 30", "/pd create 30", "random layout: how many portals"),
                            e("/pd create more", "/pd create more", "keep the portals you have and build another set"),
                            e("/pd create replacement", "/pd create replacement", "remove the old portals first, then build"),
                            e("/pd create skip-blocked", "/pd create skip-blocked", "build only the portals that fit, skip blocked spots"))),
                    List.of("Combine them: /pd create 30 more skip-blocked", "Ground or sky? /pd set mode. Size? /pd set radius."),
                    false),
            new Topic("examples", "Examples", List.of("example", "recipes"),
                    List.of("Click a line, then press Enter."),
                    List.of(),
                    List.of("30 floating portals:", "/pd set mode air", "/pd set radius 80", "/pd create 30",
                            "Floating portals at different heights:", "/pd set altitude-min 20", "/pd set altitude-max 50",
                            "Keep portals between two world heights:", "/pd set world-y-min 80", "/pd set world-y-max 180",
                            "A wall that faces north:", "/pd set layout grid", "/pd set facing north", "/pd create"),
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
                    List.of("Everything is changed with /pd set <name> <value>.", "Altitude and World Y are optional: the defaults", "already suit most worlds."),
                    sorted(List.of(
                            e("/pd set altitude-max 60", "/pd set altitude-max 60", "Altitude Max: highest floating portal above ground"),
                            e("/pd set altitude-min 15", "/pd set altitude-min 15", "Altitude Min: lowest floating portal above ground"),
                            e("/pd set center here", "/pd set center here", "Center: build around this spot (or x y z, or reset)"),
                            e("/pd set count 30", "/pd set count 30", "Count: portals the random layout makes"),
                            e("/pd set facing north", "/pd set facing north", "Facing: which way portals face (random: each its own)"),
                            e("/pd set generation delay 0.5", "/pd set generation delay 0.5", "Generation Delay: seconds between portals"),
                            e("/pd set layout grid", "/pd set layout grid", "Layout: random, grid, square or wave"),
                            e("/pd set mode air", "/pd set mode air", "Mode: ground, air, or mixed (half and half)"),
                            e("/pd set radius 80", "/pd set radius 80", "Radius: how far N, S, E and W, plus an automatic 5%"),
                            e("/pd set spacing horizontal 8", "/pd set spacing horizontal 8", "Spacing Horizontal: gap between frames side by side"),
                            e("/pd set spacing vertical 5", "/pd set spacing vertical 5", "Spacing Vertical: gap between frames above each other"),
                            e("/pd set world-y-max 320", "/pd set world-y-max 320", "World Y Max: highest Minecraft Y portals may use"),
                            e("/pd set world-y-min -64", "/pd set world-y-min -64", "World Y Min: lowest Minecraft Y portals may use"),
                            e("/pd settings", "/pd settings", "Show every value; /pd settings all shows more"))),
                    List.of("Altitude counts up from the ground nearby.", "World Y is the real Minecraft Y: a safety limit,", "not a height above the ground."),
                    false),
            new Topic("layouts", "Layouts", List.of("layout", "formations"),
                    List.of("Pick with /pd set layout, set its size, then /pd create."),
                    sorted(List.of(
                            e("/pd set layout grid", "/pd set grid rows 4", "a wall: grid rows 4 and grid columns 5 = 20"),
                            e("/pd set layout random", "/pd set layout random", "scattered: count and radius"),
                            e("/pd set layout square", "/pd set square per-side 5", "a ring: square per-side 5, layers 3 = 60"),
                            e("/pd set layout wave", "/pd set wave rows 4", "wavy rows: wave rows 4 and wave per-row 5"))),
                    List.of("Rows and layers go up; columns and sides go across.", "Facing: north, south, east, west or random;", "the square uses inward or outward.", "Random layout + random facing: each portal turns", "its own way, about a quarter in each direction.", "Every layout stays inside the radius."),
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
            new Topic("group", "Groups and cinematic scenes", List.of("players", "groups", "roster", "cinematic"),
                    List.of("Make a group, add players, give each a portal,", "then prepare: everyone stands in their portal.", "With one group you can leave its name out."),
                    sorted(List.of(
                            e("/pd group assign-to-group <player> <group>", "/pd group assign-to-group Steve Group1", "Add a player to a group"),
                            e("/pd group block <block>", "/pd group block obsidian", "Stepping on this block joins the group"),
                            e("/pd group create <name>", "/pd group create Group1", "Make a group"),
                            e("/pd group delete <name>", "/pd group delete Group1", "Delete a group; the portals stay"),
                            e("/pd group info", "/pd group info", "Each player and the portal they have"),
                            e("/pd group link-to-portal <player> <portal>", "/pd group link-to-portal Steve portal-1", "Give a player a portal (name or x y z)"),
                            e("/pd group list", "/pd group list", "Every group and its players"),
                            e("/pd group nether-travel true|false", "/pd group nether-travel false", "false: they stay inside their portal (default)"),
                            e("/pd group prepare", "/pd group prepare", "Stand every linked player in their portal"),
                            e("/pd group remove-from-group <player> <group>", "/pd group remove-from-group Steve Group1", "Take a player out and clear their portal"),
                            e("/pd group unlink-from-portal <player> <portal>", "/pd group unlink-from-portal Steve portal-1", "Take a portal away from a player"),
                            e("/pd group visibility-in-portal true|false", "/pd group visibility-in-portal false", "false hides prepared players, true shows them"),
                            e("/pd group visibility-in-portal-delay <seconds>", "/pd group visibility-in-portal-delay 0.35", "Pause between appearing groups (0.35)"))),
                    List.of("A scene: hide, prepare, then reveal:", "/pd group visibility-in-portal false", "/pd group prepare", "/pd group visibility-in-portal true",
                            "Players appear in small groups, as if they", "just arrived from the Nether."),
                    false),
            new Topic("tnt", "TNT minecarts", List.of("minecart", "minecarts"),
                    List.of("Minecarts leave through the portals."),
                    sorted(List.of(
                            e("/pd tnt clear", "/pd tnt clear", "stop and remove every TNT minecart"),
                            e("/pd tnt fuse 3", "/pd tnt fuse 3", "explode this many seconds after launch (or off)"),
                            e("/pd tnt launch all", "/pd tnt launch all", "one per portal; also floating or ground"),
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
                out.addAll(topicLinks());
            } else {
                out.add(Component.text(line, NamedTextColor.GRAY));
            }
        }
        out.add(footer(index));
        return out;
    }

    /** The topic names as clickable words, over two lines so they fit the chat window. */
    private static List<Component> topicLinks() {
        List<String> names = topicNames();
        int half = (names.size() + 1) / 2;
        List<Component> out = new ArrayList<>();
        for (int row = 0; row < 2; row++) {
            Component c = Component.text(row == 0 ? "Topics: " : "        ", NamedTextColor.GRAY);
            List<String> part = names.subList(row == 0 ? 0 : half, row == 0 ? half : names.size());
            for (int i = 0; i < part.size(); i++) {
                c = c.append(click(part.get(i), NamedTextColor.YELLOW, ClickEvent.runCommand("/pd help " + part.get(i)), "Open /pd help " + part.get(i)));
                if (i < part.size() - 1) c = c.append(Component.text(" ", NamedTextColor.GRAY));
            }
            out.add(c);
        }
        return out;
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
