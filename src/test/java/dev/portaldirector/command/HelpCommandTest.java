package dev.portaldirector.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class HelpCommandTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static boolean has(Component c, ClickEvent.Action action, String value) {
        ClickEvent ev = c.clickEvent();
        if (ev != null && ev.action() == action && ev.payload() instanceof ClickEvent.Payload.Text t && t.value().equals(value)) return true;
        return c.children().stream().anyMatch(ch -> has(ch, action, value));
    }

    @Test
    void createOptionsAreTheNewNamesAlphabetically() {
        assertEquals(List.of("more", "replacement", "skip-blocked"), GenerateCommands.CREATE_OPTIONS);
        for (String old : List.of("add", "replace", "partial", "air", "ground", "mixed")) {
            assertFalse(GenerateCommands.CREATE_OPTIONS.contains(old), old);
        }
    }

    @Test
    void createArgumentsCombineWithACountInAnyOrder() {
        var a = GenerateCommands.CreateArgs.parse(List.of("30", "more", "skip-blocked"));
        assertEquals(30, a.count());
        assertTrue(a.more() && a.skipBlocked() && !a.replacement());
        var b = GenerateCommands.CreateArgs.parse(List.of("SKIP-blocked", "Replacement", "50"));
        assertEquals(50, b.count());
        assertTrue(b.replacement() && b.skipBlocked() && !b.more());
        var c = GenerateCommands.CreateArgs.parse(List.of());
        assertNull(c.count());
        assertFalse(c.more() || c.replacement() || c.skipBlocked());
    }

    @Test
    void conflictingOrRepeatedArgumentsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> GenerateCommands.CreateArgs.parse(List.of("more", "replacement")));
        assertThrows(IllegalArgumentException.class, () -> GenerateCommands.CreateArgs.parse(List.of("30", "40")));
        assertThrows(IllegalArgumentException.class, () -> GenerateCommands.CreateArgs.parse(List.of("portal")));
    }

    @Test
    void oldNamesAndPlacementWordsAreRefusedWithTheNewWayToSayIt() {
        assertTrue(msg("add").contains("more"));
        assertTrue(msg("replace").contains("replacement"));
        assertTrue(msg("partial").contains("skip-blocked"));
        for (String mode : List.of("air", "ground", "mixed")) assertTrue(msg(mode).contains("/pd set mode " + mode), mode);
    }

    private static String msg(String arg) {
        return assertThrows(IllegalArgumentException.class, () -> GenerateCommands.CreateArgs.parse(List.of(arg))).getMessage();
    }

    @Test
    void everyPageFitsTheChatWindow() {
        assertTrue(HelpCommand.PAGES.size() >= 6);
        for (int i = 0; i < HelpCommand.PAGES.size(); i++) {
            List<Component> lines = HelpCommand.render(i);
            assertTrue(lines.size() <= 18, "page " + (i + 1) + " has " + lines.size() + " lines");
            for (Component line : lines) {
                String t = plain(line);
                assertTrue(t.length() <= 58, "page " + (i + 1) + " line too long (" + t.length() + "): " + t);
            }
        }
    }

    @Test
    void everyPageShowsItsNumberAndWorkingNavigation() {
        int total = HelpCommand.PAGES.size();
        for (int i = 0; i < total; i++) {
            List<Component> lines = HelpCommand.render(i);
            Component footer = lines.get(lines.size() - 1);
            assertTrue(plain(footer).contains("Page " + (i + 1) + "/" + total), plain(footer));
            assertEquals(i > 0, has(footer, ClickEvent.Action.RUN_COMMAND, "/pd help " + i), "previous on page " + (i + 1));
            assertEquals(i < total - 1, has(footer, ClickEvent.Action.RUN_COMMAND, "/pd help " + (i + 2)), "next on page " + (i + 1));
        }
    }

    @Test
    void commandsInATopicAreAlphabeticalAndEveryOneIsClickable() {
        for (HelpCommand.Topic t : HelpCommand.TOPICS) {
            List<String> names = t.entries().stream().map(en -> en.command().toLowerCase(Locale.ROOT)).toList();
            if (!t.key().equals("start")) {
                List<String> sorted = new ArrayList<>(names);
                sorted.sort(String::compareTo);
                assertEquals(sorted, names, t.key());
            }
            for (HelpCommand.Entry en : t.entries()) {
                assertTrue(en.insert().startsWith("/pd "), en.insert());
                assertFalse(en.what().isBlank());
            }
        }
        for (int i = 0; i < HelpCommand.PAGES.size(); i++) {
            List<Component> lines = HelpCommand.render(i);
            for (HelpCommand.Entry en : HelpCommand.PAGES.get(i).entries()) {
                assertTrue(lines.stream().anyMatch(l -> has(l, ClickEvent.Action.SUGGEST_COMMAND, en.insert())), en.command());
            }
        }
    }

    @Test
    void topicsResolveToTheirFirstPageAndMatchTabCompletion() {
        for (String name : HelpCommand.topicNames()) assertTrue(HelpCommand.pageOfTopic(name) > 0, name);
        assertEquals(List.of("create", "examples", "group", "layouts", "portals", "reveal", "scenes", "set", "tnt"), HelpCommand.topicNames());
        assertEquals(0, HelpCommand.pageOfTopic("start"));
        assertEquals(HelpCommand.pageOfTopic("create"), HelpCommand.pageOfTopic("generate"));
        assertEquals(-1, HelpCommand.pageOfTopic("nonsense"));
        List<String> tab = new HelpCommand().tab(null, List.of(""));
        assertTrue(tab.containsAll(HelpCommand.topicNames()));
        assertTrue(tab.contains("1") && tab.contains(String.valueOf(HelpCommand.PAGES.size())));
    }

    @Test
    void theStartPageTeachesTheFiveStepWorkflowInOrder() {
        String all = String.join("\n", HelpCommand.render(0).stream().map(HelpCommandTest::plain).toList());
        int last = -1;
        for (String step : List.of("/pd set", "/pd create", "/pd list", "/pd remove", "/pd clear")) {
            int at = all.indexOf(step);
            assertTrue(at > last, step);
            last = at;
        }
    }

    @Test
    void helpDoesNotMentionRemovedOptions() {
        for (int i = 0; i < HelpCommand.PAGES.size(); i++) {
            String all = String.join("\n", HelpCommand.render(i).stream().map(HelpCommandTest::plain).toList());
            for (String old : List.of("/pd create add", "/pd create replace\n", "/pd create partial", "/pd create air", "/pd create ground", "/pd create mixed", "/pd generate",
                    "orientation", "air-ratio", "slope", "height-min", "height-max", "min-y", "max-y", "staging", "entrance", "group assign ", "group add", "group remove ", "group disband", "group release", "group reset")) {
                assertFalse(all.contains(old), old);
            }
        }
    }
}
