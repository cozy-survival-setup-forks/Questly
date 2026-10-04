package dev.questly;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    private static final Logger LOG = Logger.getAnonymousLogger();

    private static YamlConfiguration bundled() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                SettingsTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    @Test
    void theBundledConfigIsReadAsIntended() {
        Settings settings = new Settings(bundled(), LOG);

        assertEquals(3, settings.menuRows());
        assertEquals(List.of(9, 10, 11, 12, 13, 14, 15, 16, 17), settings.questSlots());
        assertEquals(9, settings.slots());
        assertTrue(settings.categoryDecorations().isEmpty());
        assertTrue(settings.boardDecorations().isEmpty());
        assertEquals(3, settings.categoriesRows());
        assertEquals(22, settings.backSlot());
    }

    @Test
    void anOldConfigWithGuiAndBoardSlotsStillWorks() {
        YamlConfiguration old = new YamlConfiguration();
        old.set("board.slots", 5);
        old.set("gui.title", "Old board");
        Settings settings = new Settings(old, LOG);

        assertEquals(5, settings.slots());
        assertEquals("Old board", settings.guiTitle());
        assertEquals(3, settings.menuRows());
    }
}
