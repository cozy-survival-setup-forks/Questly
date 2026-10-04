package dev.questly.gui;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopParserTest {

    private static Map<Integer, ShopParser.Product> parse(String text, List<String> warnings) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        Logger log = new Logger("test", null) {
            @Override
            public void warning(String msg) {
                warnings.add(msg);
            }
        };
        return ShopParser.parse(yaml.getConfigurationSection("items"), 54, log, name -> !name.equals("NOT_AN_ITEM"));
    }

    @Test
    void aFreeButtonIsKeptWithItsLoreAndCommands() throws Exception {
        List<String> warnings = new ArrayList<>();
        Map<Integer, ShopParser.Product> products = parse("""
                items:
                  return:
                    slot: 27
                    price: 0
                    material: globe_banner_pattern
                    display_name: '&#FF4361&lRETURN'
                    lore: ['&fClick to go back', '%price%']
                    left_click_commands: ['[console] dm open quests %player%', '[close]']
                """, warnings);

        assertTrue(warnings.isEmpty(), warnings.toString());
        ShopParser.Product product = products.get(27);
        assertTrue(product.free());
        assertEquals("GLOBE_BANNER_PATTERN", product.material());
        assertEquals(List.of("&fClick to go back", "%price%"), product.lore());
        assertEquals(List.of("[console] dm open quests %player%", "[close]"), product.commands());
    }

    @Test
    void aDecorationWithNoPriceAndNoCommandsIsKept() throws Exception {
        List<String> warnings = new ArrayList<>();
        Map<Integer, ShopParser.Product> products = parse("""
                items:
                  pane:
                    slot: 0
                    material: GRAY_STAINED_GLASS_PANE
                    display_name: ' '
                """, warnings);

        assertTrue(warnings.isEmpty(), warnings.toString());
        assertTrue(products.get(0).free());
        assertTrue(products.get(0).commands().isEmpty());
    }

    @Test
    void aNegativePriceIsSkippedWithAWarningNamingTheEntry() throws Exception {
        List<String> warnings = new ArrayList<>();
        Map<Integer, ShopParser.Product> products = parse("""
                items:
                  cheap:
                    slot: 1
                    price: -3
                    material: DIAMOND
                    left_click_commands: ['say hi']
                """, warnings);

        assertTrue(products.isEmpty());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("cheap"));
    }

    @Test
    void aPaidProductStillHasToGiveSomething() throws Exception {
        List<String> warnings = new ArrayList<>();
        Map<Integer, ShopParser.Product> products = parse("""
                items:
                  nothing:
                    slot: 1
                    price: 5
                    material: DIAMOND
                  diamonds:
                    slot: 2
                    price: 10
                    material: DIAMOND
                    give:
                      - material: DIAMOND
                        amount: 5
                """, warnings);

        assertFalse(products.containsKey(1));
        assertEquals(10, products.get(2).price());
        assertFalse(products.get(2).free());
        assertEquals(1, products.get(2).give().size());
        assertEquals(1, warnings.size());
    }

    @Test
    void badSlotsMaterialsAndGiveLinesAreSkipped() throws Exception {
        List<String> warnings = new ArrayList<>();
        Map<Integer, ShopParser.Product> products = parse("""
                items:
                  outside:
                    slot: 54
                    price: 0
                  unknown:
                    slot: 3
                    material: not_an_item
                  brokenGive:
                    slot: 4
                    price: 2
                    material: DIAMOND
                    give:
                      - material: NOT_AN_ITEM
                """, warnings);

        assertTrue(products.isEmpty());
        assertEquals(4, warnings.size());
    }
}
