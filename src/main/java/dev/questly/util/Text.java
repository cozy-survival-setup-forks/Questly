package dev.questly.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turning the texts of the config files into components. Old & codes, &#rrggbb and MiniMessage all work. */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final Pattern CODE = Pattern.compile("&([0-9a-fk-orA-FK-OR])");
    private static final String[] COLORS = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};

    private static final Pattern TAG = Pattern.compile("^\\[(\\w+)]\\s*(.*)$");

    /** A DeluxeMenus-style command line split into its {@code [tag]} and the rest. The tag is empty when there is none. */
    public record Tagged(String tag, String rest) {
    }

    /** Splits a line such as {@code "[console] give %player% diamond 1"}. */
    public static Tagged tag(String line) {
        Matcher matcher = TAG.matcher(line.trim());
        return matcher.matches() ? new Tagged(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2)) : new Tagged("", line.trim());
    }

    private Text() {
    }

    /** Turns &amp; codes into MiniMessage tags. */
    public static String convertLegacy(String text) {
        String result = HEX.matcher(text).replaceAll("<#$1>");
        return CODE.matcher(result).replaceAll(match -> Matcher.quoteReplacement(tagFor(match.group(1).charAt(0))));
    }

    private static String tagFor(char code) {
        char lower = Character.toLowerCase(code);
        int index = "0123456789abcdef".indexOf(lower);
        if (index >= 0) return "<" + COLORS[index] + ">";
        return switch (lower) {
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            default -> "<reset>";
        };
    }

    public static Component component(String text) {
        return MINI.deserialize(convertLegacy(text));
    }

    /** For item names and lore, which are italic unless told otherwise. */
    public static Component item(String text) {
        return component(text).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Fills in %name% style placeholders. */
    public static String fill(String text, Map<String, String> values) {
        String result = text;
        for (Map.Entry<String, String> value : values.entrySet()) {
            result = result.replace(value.getKey(), value.getValue());
        }
        return result;
    }

    /** A number as text, without a needless ".0". */
    public static String number(double value) {
        if (value == Math.rint(value)) return String.valueOf((long) value);
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
