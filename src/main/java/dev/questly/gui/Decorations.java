package dev.questly.gui;

import dev.questly.util.Slots;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * The extra items a menu can show next to its buttons: a name and lore, and optionally commands to run on a click.
 * Read from a {@code decorations:} list in config.yml. An entry with a problem is skipped and the reason is logged.
 */
public final class Decorations {

    /** One decoration, shown in every slot of {@code slots}. */
    public record Decoration(List<Integer> slots, String material, String name, List<String> lore, List<String> commands) {
    }

    private Decorations() {
    }

    /**
     * @param raw    the list from the config, each entry a map
     * @param size   the number of slots of the menu
     * @param where  the config path, for the log
     * @param isItem whether a material name is a real item
     */
    public static List<Decoration> parse(List<Map<?, ?>> raw, int size, String where, Logger log, Predicate<String> isItem) {
        List<Decoration> result = new ArrayList<>();
        int number = 0;
        for (Map<?, ?> entry : raw) {
            number++;
            String label = where + " #" + number;

            Object material = entry.get("material");
            String name = material == null ? "" : String.valueOf(material).trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty() || !isItem.test(name)) {
                log.warning("config.yml: " + label + " shows the item '" + (material == null ? "" : material) + "', which does not exist. Skipping it.");
                continue;
            }

            Object where_ = entry.containsKey("slots") ? entry.get("slots") : entry.get("slot");
            List<Integer> slots = Slots.parse(where_, size, problem -> log.warning("config.yml: " + label + ": " + problem + ", leaving it out."));
            if (slots.isEmpty()) {
                log.warning("config.yml: " + label + " has no usable slot. Skipping it.");
                continue;
            }

            Object title = entry.get("name");
            result.add(new Decoration(List.copyOf(slots), name, title == null ? " " : String.valueOf(title),
                    strings(entry.get("lore")), strings(entry.get("commands"))));
        }
        return List.copyOf(result);
    }

    /** The decoration shown in a slot. When two cover the same slot, the later one wins. */
    public static @Nullable Decoration at(List<Decoration> decorations, int slot) {
        for (int i = decorations.size() - 1; i >= 0; i--) {
            if (decorations.get(i).slots().contains(slot)) return decorations.get(i);
        }
        return null;
    }

    private static List<String> strings(@Nullable Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object each : list) result.add(String.valueOf(each));
            return List.copyOf(result);
        }
        return value == null ? List.of() : List.of(String.valueOf(value));
    }
}
