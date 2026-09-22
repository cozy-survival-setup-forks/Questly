package dev.questly.util;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextTest {

    @Test
    void oldCodesAndHexColoursBecomeMiniMessage() {
        assertEquals("<#A8E6A3>green <white>text", Text.convertLegacy("&#A8E6A3green &ftext"));
        assertEquals("<bold><red>x", Text.convertLegacy("&l&cx"));
    }

    @Test
    void theTextOfTheQuestLoreSurvivesTheConversion() {
        String line = "&#AAA5FF● &fProgress: &#AAA5FF%score%/%required%";
        String filled = Text.fill(line, Map.of("%score%", "12", "%required%", "75"));

        assertEquals("● Progress: 12/75", PlainTextComponentSerializer.plainText().serialize(Text.component(filled)));
    }

    @Test
    void itemTextIsNotItalic() {
        assertEquals(net.kyori.adventure.text.format.TextDecoration.State.FALSE,
                Text.item("hello").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC));
    }

    @Test
    void placeholdersAreFilledIn() {
        assertEquals("Kill 75 Creepers", Text.fill("Kill %required% Creepers", Map.of("%required%", "75")));
    }

    @Test
    void numbersDropTheNeedlessDecimal() {
        assertEquals("12", Text.number(12));
        assertEquals("12.5", Text.number(12.5));
        assertEquals("0", Text.number(0));
    }

    @Test
    void commandLinesSplitIntoTheirTagAndRest() {
        assertEquals("console", Text.tag("[console] give %player% diamond 1").tag());
        assertEquals("give %player% diamond 1", Text.tag("[console] give %player% diamond 1").rest());
        assertEquals("player", Text.tag("[player] spawn").tag());
        assertEquals("", Text.tag("give %player% diamond 1").tag());
        assertEquals("give %player% diamond 1", Text.tag("give %player% diamond 1").rest());
    }

    @Test
    void timesAreShownAsAClock() {
        assertEquals("00:00", Duration.clock(0));
        assertEquals("00:01", Duration.clock(1));
        assertEquals("01:05", Duration.clock(65_000));
        assertEquals("1:25:03", Duration.clock(5_103_000));
        assertTrue(Duration.clock(-5).startsWith("00"));
    }
}
