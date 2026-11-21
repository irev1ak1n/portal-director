package dev.portaldirector;

import dev.portaldirector.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageStyleTest {

    private final List<Component> sent = new ArrayList<>();

    private CommandSender sender() {
        return (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
            if (method.getName().equals("sendMessage") && args != null && args[0] instanceof Component c) sent.add(c);

            return null;
        });
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @AfterEach
    void back() {
        Msg.reset();
    }

    @Test
    void theDefaultLookIsTheOriginalOne() {
        Component original = Component.text("[PD] ", TextColor.color(0xB06CFF)).append(Component.text("done", NamedTextColor.GREEN));
        Msg.ok(sender(), "done");

        assertEquals(plain(original), plain(sent.get(0)));
        assertEquals(TextColor.color(0xB06CFF), sent.get(0).children().get(0).color(), "the prefix colour");
        assertEquals(NamedTextColor.GREEN, sent.get(0).children().get(1).color(), "the message colour");
    }

    @Test
    void everyKindOfMessageReadsTheSameAsBefore() {
        CommandSender s = sender();
        Msg.info(s, "a");
        Msg.err(s, "b");
        Msg.line(s, "c");
        Msg.head(s, "Title");
        Msg.cmd(s, "/pd x", "does x");

        assertEquals(List.of("[PD] a", "[PD] b", "c", "--- Title ---", "/pd x  does x"), sent.stream().map(MessageStyleTest::plain).toList());
    }

    @Test
    void aConfiguredPrefixAndColourAreUsed() {
        List<String> problems = new ArrayList<>();
        Msg.configure(Map.of("prefix", "<gold>[Show] </gold>", "ok", "<prefix><aqua><text></aqua>"), problems::add);
        Msg.ok(sender(), "ready");

        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals("[Show] ready", plain(sent.get(0)));
        assertEquals(NamedTextColor.GOLD, sent.get(0).children().get(0).color());
    }

    @Test
    void typedTextCanNeverAddTagsOrClicks() {
        Msg.ok(sender(), "<red><click:run_command:/op me>pwn</click> <rainbow>x");

        assertEquals("[PD] <red><click:run_command:/op me>pwn</click> <rainbow>x", plain(sent.get(0)));
        assertTrue(sent.get(0).children().stream().allMatch(c -> c.clickEvent() == null));
    }

    @Test
    void theSameGoesForTheCommandUsageAndDescription() {
        Msg.cmd(sender(), "<bold>/pd", "<click:open_url:http://evil>link");

        assertEquals("<bold>/pd  <click:open_url:http://evil>link", plain(sent.get(0)));
    }

    @Test
    void aBrokenTemplateFallsBackToTheDefaultAndSaysWhy() {
        List<String> problems = new ArrayList<>();
        Msg.configure(Map.of("ok", "<prefix>no placeholder here", "info", "", "colour", "<red><text>"), problems::add);
        Msg.ok(sender(), "still fine");
        Msg.info(sender(), "also fine");

        assertEquals(3, problems.size(), problems.toString());
        assertEquals("[PD] still fine", plain(sent.get(0)));
        assertEquals("[PD] also fine", plain(sent.get(1)));
    }

    @Test
    void aTemplateThatInjectsAnotherStyleThroughThePrefixIsStillJustText() {
        List<String> problems = new ArrayList<>();
        Msg.configure(Map.of("prefix", "<red>[X] </red>"), problems::add);
        Msg.err(sender(), "bad");

        assertEquals("[X] bad", plain(sent.get(0)));
    }

    @Test
    void resetBringsBackTheDefaults() {
        Msg.configure(Map.of("prefix", "<red>[X] </red>"), p -> { });
        Msg.reset();
        Msg.ok(sender(), "x");

        assertEquals("[PD] x", plain(sent.get(0)));
    }
}
