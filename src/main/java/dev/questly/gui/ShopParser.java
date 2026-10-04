package dev.questly.gui;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Logger;

/** Reads the items of shop.yml. An item with a problem is skipped and the reason is logged. */
public final class ShopParser {

    /**
     * One thing in the shop. A price of 0 makes it a button or a decoration: clicking it runs its commands and
     * hands over its {@code give} items, takes no points and only says "bought" when it gives an item.
     */
    public record Product(String id, int slot, int price, String material, String name, List<String> lore,
                          List<String> commands, List<ConfigurationSection> give) {

        public boolean free() {
            return price == 0;
        }
    }

    private ShopParser() {
    }

    /**
     * The products by inventory slot.
     *
     * @param size   the number of slots of the shop
     * @param isItem whether a material name is a real item
     */
    public static Map<Integer, Product> parse(@Nullable ConfigurationSection items, int size, Logger log, Predicate<String> isItem) {
        Map<Integer, Product> products = new LinkedHashMap<>();
        if (items == null) return products;
        for (String id : items.getKeys(false)) {
            ConfigurationSection entry = items.getConfigurationSection(id);
            if (entry == null) continue;

            int slot = entry.getInt("slot", -1);
            // No price at all is a decoration too, the same as price: 0.
            int price = entry.getInt("price", 0);
            String material = entry.getString("material", "PAPER").toUpperCase(Locale.ROOT);
            List<String> commands = entry.contains("left_click_commands")
                    ? entry.getStringList("left_click_commands") : entry.getStringList("commands");
            List<ConfigurationSection> give = new ArrayList<>();
            boolean giveOk = true;
            for (Map<?, ?> map : entry.getMapList("give")) {
                YamlConfiguration line = new YamlConfiguration();
                map.forEach((key, value) -> line.set(String.valueOf(key), value));
                String giveMaterial = line.getString("material", "STONE").toUpperCase(Locale.ROOT);
                if (!isItem.test(giveMaterial)) {
                    log.warning("shop.yml: " + id + " has a give line with '" + giveMaterial
                            + "', which is not an item. Skipping it.");
                    giveOk = false;
                    continue;
                }
                give.add(line);
            }

            if (slot < 0 || slot >= size) {
                log.warning("shop.yml: " + id + " has the slot " + slot + ", which is not inside the shop. Skipping it.");
            } else if (price < 0) {
                log.warning("shop.yml: " + id + " has the price " + price + ", which is below 0. Skipping it.");
            } else if (!isItem.test(material)) {
                log.warning("shop.yml: " + id + " shows '" + material + "', which is not an item. Skipping it.");
            } else if (!giveOk) {
                log.warning("shop.yml: " + id + " has a broken give line - fix it before this product is sold, "
                        + "or a player would pay and get only the working parts. Skipping it.");
            } else if (price > 0 && commands.isEmpty() && give.isEmpty()) {
                log.warning("shop.yml: " + id + " costs points but gives nothing, add left_click_commands or give. Skipping it.");
            } else {
                String name = entry.contains("display_name") ? entry.getString("display_name", id) : entry.getString("name", id);
                products.put(slot, new Product(id, slot, price, material, name, entry.getStringList("lore"), commands, give));
            }
        }
        return products;
    }
}
