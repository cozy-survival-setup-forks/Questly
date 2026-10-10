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
        Settings settings = new Settings(bundled(), LOG, name -> true);

        assertEquals(6, settings.menuRows());
        assertEquals(28, settings.slots());
        assertEquals(List.of(10, 11, 12, 13, 14, 15, 16), settings.questSlots().subList(0, 7));
        assertEquals(List.of(37, 38, 39, 40, 41, 42, 43), settings.questSlots().subList(21, 28));
        assertEquals("NONE", settings.filler());
        assertEquals(6, settings.categoriesRows());
        assertEquals(45, settings.backSlot());
        assertEquals(1, settings.categoryDecorations().size());
        assertEquals(List.of(49), settings.categoryDecorations().get(0).slots());
        assertEquals(List.of("[player] questshop"), settings.boardDecorations().get(0).commands());
        assertEquals(19, settings.categoryButton(dev.questly.quest.Category.COMBAT).slot());
        assertEquals(25, settings.categoryButton(dev.questly.quest.Category.MISC).slot());
    }

    @Test
    void aCategoryCanHaveATitleOfItsOwn() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("categories.combat.name", "&cCombat Quests");
        config.set("categories.combat.title", "&6Fighting - %category%");
        Settings settings = new Settings(config, LOG);

        assertEquals("&6Fighting - %category%", settings.categoryBoardTitle(dev.questly.quest.Category.COMBAT));
        assertEquals(null, settings.categoryBoardTitle(dev.questly.quest.Category.MINING));
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
