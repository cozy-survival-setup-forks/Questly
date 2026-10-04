package dev.questly.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotsTest {

    private static List<Integer> parse(Object value, int size, List<String> warnings) {
        return Slots.parse(value, size, warnings::add);
    }

    @Test
    void aListAndRangesAreReadInOrder() {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(11, 13, 15), parse(List.of(11, 13, 15), 27, warnings));
        assertEquals(List.of(9, 10, 11, 12), parse("9-12", 27, warnings));
        assertEquals(List.of(1, 2, 5, 9, 10), parse("1-2, 5, 9-10", 27, warnings));
        assertEquals(List.of(4, 6, 7, 8), parse(List.of(4, "6-8"), 27, warnings));
        assertEquals(List.of(13), parse(13, 27, warnings));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void badSlotsAreLeftOutWithAWarning() {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(3), parse(List.of(3, 3, 40, -1, "x"), 27, warnings));
        assertEquals(4, warnings.size());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("listed twice")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("not a slot")));

        warnings.clear();
        assertTrue(parse("12-10", 27, warnings).isEmpty());
        assertFalse(warnings.isEmpty());
    }

    @Test
    void theOldStyleSlotsStillWork() {
        assertEquals(List.of(9, 10, 11), Slots.legacySlots(3, 27, List.of()));
        assertEquals(List.of(2, 4, 6), Slots.legacySlots(3, 27, List.of(2, 4, 6)));
        // not enough valid slots: the middle rows are used
        assertEquals(List.of(9, 10, 11), Slots.legacySlots(3, 27, List.of(2, 4)));
        assertEquals(3, Slots.legacyRows(9, 0));
        assertEquals(6, Slots.legacyRows(54, 0));
        assertEquals(4, Slots.legacyRows(9, 4));
        assertEquals(List.of(9, 10, 11, 12, 13, 14, 15, 16, 17), Slots.middleRow(3));
    }
}
