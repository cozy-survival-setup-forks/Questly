package dev.questly.util;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Reads the inventory slots of a menu from the config: a list such as {@code [11, 13, 15]} or a range such as {@code "10-16"}. */
public final class Slots {

    private Slots() {
    }

    /**
     * The slots, in order. {@code value} is a list (of numbers or ranges), a single number or a text such as
     * {@code "10-12, 14"}. Slots outside a menu of {@code size} slots, repeated slots and anything that is not a
     * slot are left out with a warning.
     */
    public static List<Integer> parse(@Nullable Object value, int size, Consumer<String> warn) {
        List<String> parts = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object part : list) parts.add(String.valueOf(part));
        } else if (value != null) {
            for (String part : String.valueOf(value).split(",")) parts.add(part);
        }

        List<Integer> slots = new ArrayList<>();
        for (String raw : parts) {
            String part = raw.trim();
            if (part.isEmpty()) continue;
            int from;
            int to;
            try {
                int dash = part.indexOf('-', 1);
                from = Integer.parseInt((dash < 0 ? part : part.substring(0, dash)).trim());
                to = dash < 0 ? from : Integer.parseInt(part.substring(dash + 1).trim());
            } catch (NumberFormatException e) {
                warn.accept("'" + part + "' is not a slot or a range such as 10-16");
                continue;
            }
            if (to < from) {
                warn.accept("the range " + part + " goes backwards");
                continue;
            }
            for (int slot = from; slot <= to; slot++) {
                if (slot < 0 || slot >= size) {
                    warn.accept("slot " + slot + " is not inside a menu of " + size + " slots (0 to " + (size - 1) + ")");
                } else if (slots.contains(slot)) {
                    warn.accept("slot " + slot + " is listed twice");
                } else {
                    slots.add(slot);
                }
            }
        }
        return slots;
    }

    /** The middle row of a menu with this many rows, for when no slot is usable. */
    public static List<Integer> middleRow(int rows) {
        int first = ((rows - 1) / 2) * 9;
        List<Integer> slots = new ArrayList<>();
        for (int slot = first; slot < first + 9; slot++) slots.add(slot);
        return slots;
    }

    /** Rows of a board in the old {@code gui.rows} style: 0 fits the quests with a filler row above and below. */
    public static int legacyRows(int quests, int configuredRows) {
        int needed = Math.max(1, (quests + 8) / 9);
        int rows = configuredRows <= 0 ? Math.min(6, needed + 2) : configuredRows;
        return Math.max(needed, Math.min(6, rows));
    }

    /**
     * The slots of the quests in the old {@code gui.quest-slots} style: the configured slots if there are enough
     * valid ones, otherwise the quests fill the rows in the middle of the board.
     */
    public static List<Integer> legacySlots(int quests, int size, List<Integer> configured) {
        List<Integer> valid = new ArrayList<>();
        for (int slot : configured) {
            if (slot >= 0 && slot < size && !valid.contains(slot)) valid.add(slot);
        }
        if (valid.size() >= quests) return List.copyOf(valid.subList(0, quests));

        int rows = size / 9;
        int used = (quests + 8) / 9;
        int first = ((rows - used) / 2) * 9;
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < quests; i++) slots.add(first + i);
        return slots;
    }
}
