package dev.questly.gui;

import dev.questly.gui.Decorations.Decoration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DecorationsTest {

    private final List<String> warnings = new ArrayList<>();
    private final Logger log = capture();

    private Logger capture() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        return logger;
    }

    private List<Decoration> parse(Map<?, ?>... entries) {
        return Decorations.parse(List.of(entries), 27, "categories.decorations", log, name -> !name.equals("NOPE"));
    }

    @Test
    void anEntryHasSlotsMaterialNameLoreAndCommands() {
        List<Decoration> parsed = parse(Map.of("slots", "0-2", "material", "stone", "name", "Hi",
                "lore", List.of("a", "b"), "commands", List.of("[player] spawn")));

        assertEquals(1, parsed.size());
        assertEquals(List.of(0, 1, 2), parsed.get(0).slots());
        assertEquals("STONE", parsed.get(0).material());
        assertEquals("Hi", parsed.get(0).name());
        assertEquals(List.of("a", "b"), parsed.get(0).lore());
        assertEquals(List.of("[player] spawn"), parsed.get(0).commands());
        assertEquals(List.of(), warnings);
    }

    @Test
    void aSingleSlotNumberAndDefaultsWork() {
        List<Decoration> parsed = parse(Map.of("slot", 4, "material", "BOOK"));
        assertEquals(List.of(4), parsed.get(0).slots());
        assertEquals(" ", parsed.get(0).name());
        assertEquals(List.of(), parsed.get(0).lore());
        assertEquals(List.of(), parsed.get(0).commands());
    }

    @Test
    void brokenEntriesAreSkippedWithAWarning() {
        List<Decoration> parsed = parse(
                Map.of("slot", 1, "material", "NOPE"),
                Map.of("slot", 1),
                Map.of("slot", 99, "material", "STONE"),
                Map.of("material", "STONE"),
                Map.of("slot", 2, "material", "STONE"));

        assertEquals(1, parsed.size());
        assertEquals(List.of(2), parsed.get(0).slots());
        assertEquals(true, warnings.size() >= 4);
    }

    @Test
    void theLaterDecorationWinsASharedSlot() {
        List<Decoration> parsed = parse(Map.of("slots", "0-4", "material", "STONE"), Map.of("slot", 2, "material", "DIRT"));
        assertEquals("STONE", Decorations.at(parsed, 1).material());
        assertSame(parsed.get(1), Decorations.at(parsed, 2));
        assertNull(Decorations.at(parsed, 20));
    }
}
