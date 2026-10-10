package dev.questly;

import dev.questly.gui.Decorations;
import dev.questly.quest.Category;
import dev.questly.quest.Quest;
import dev.questly.util.Slots;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** The values of config.yml. */
public final class Settings {

    private final FileConfiguration config;
    private final int menuRows;
    private final List<Integer> questSlots;
    /** True for a config.yml from before the menu: section. Checked on the file itself, not the bundled defaults. */
    private final boolean legacy;
    private final List<Decorations.Decoration> categoryDecorations;
    private final List<Decorations.Decoration> boardDecorations;

    Settings(FileConfiguration config, Logger log) {
        this(config, log, Settings::isItem);
    }

    /** {@code isItem} tells whether a material name is a real item, so tests need no server. */
    Settings(FileConfiguration config, Logger log, java.util.function.Predicate<String> isItem) {
        this.config = config;
        legacy = !config.contains("menu", true);
        if (!legacy) {
            menuRows = Math.max(1, Math.min(6, config.getInt("menu.rows", 3)));
            List<Integer> slots = Slots.parse(config.get("menu.quest-slots"), menuRows * 9,
                    problem -> log.warning("config.yml: menu.quest-slots: " + problem + ", leaving it out."));
            if (slots.isEmpty()) {
                log.warning("config.yml: menu.quest-slots has no usable slot, using the middle row.");
                slots = Slots.middleRow(menuRows);
            }
            questSlots = List.copyOf(slots);
        } else {
            // Before 1.2.0: board.slots, gui.rows and gui.quest-slots.
            int quests = Math.max(1, Math.min(54, config.getInt("board.slots", 9)));
            menuRows = Slots.legacyRows(quests, Math.max(0, Math.min(6, config.getInt("gui.rows", 0))));
            questSlots = Slots.legacySlots(quests, menuRows * 9, config.getIntegerList("gui.quest-slots"));
            log.info("config.yml still uses board.slots and gui:, which keep working. The simpler menu: section "
                    + "(rows, quest-slots, filler) is shown in the default config.yml.");
        }
        categoryDecorations = Decorations.parse(config.getMapList("categories.decorations"), categoriesRows() * 9,
                "categories.decorations", log, isItem);
        boardDecorations = Decorations.parse(config.getMapList(menu("decorations")), menuRows * 9,
                menu("decorations"), log, isItem);
    }

    /** The extra items of the category menu. */
    public List<Decorations.Decoration> categoryDecorations() {
        return categoryDecorations;
    }

    /** The extra items of a board. */
    public List<Decorations.Decoration> boardDecorations() {
        return boardDecorations;
    }

    private static boolean isItem(String name) {
        org.bukkit.Material type = org.bukkit.Material.matchMaterial(name);
        return type != null && type.isItem();
    }

    /** How many quests are up at once: one for every quest slot of the menu. */
    public int slots() {
        return questSlots.size();
    }

    public long cooldownSeconds() {
        return Math.max(0, config.getLong("board.cooldown-seconds", 1800));
    }

    public boolean uniqueQuests() {
        return config.getBoolean("board.unique-quests", true);
    }

    public boolean countCreative() {
        return config.getBoolean("board.count-creative", false);
    }

    public int saveSeconds() {
        return Math.max(5, config.getInt("save-seconds", 60));
    }

    public int chatMinLength() {
        return Math.max(1, config.getInt("chat.min-length", 3));
    }

    public long chatCooldownMillis() {
        return Math.max(0, config.getLong("chat.cooldown-seconds", 3)) * 1000L;
    }

    /** {@code key} of the menu section, or of the older gui section. */
    private String menu(String key) {
        return (legacy ? "gui." : "menu.") + key;
    }

    public String guiTitle() {
        return config.getString(menu("title"), "Quest Board");
    }

    public int menuRows() {
        return menuRows;
    }

    /** The inventory slot of every quest, in order. */
    public List<Integer> questSlots() {
        return questSlots;
    }

    /** The material of the empty slots of the board, or NONE to leave them empty. */
    public String filler() {
        return config.getString(menu("filler"), "BLACK_STAINED_GLASS_PANE").toUpperCase(Locale.ROOT);
    }

    /** What is shown where a quest was won and the next one has not started. */
    public Quest.Display cooldownItem() {
        ConfigurationSection section = config.getConfigurationSection(menu("cooldown-item"));
        if (section == null) return new Quest.Display("CLOCK", "Next quest in %time%", List.of());
        return new Quest.Display(section.getString("material", "CLOCK").toUpperCase(Locale.ROOT),
                section.getString("name", "Next quest in %time%"), section.getStringList("lore"));
    }

    /** Where a category sits in the category menu and what it looks like. */
    public record CategoryButton(int slot, Quest.Display display) {
    }

    public String categoriesTitle() {
        return config.getString("categories.title", "&#FF9558Quests");
    }

    public int categoriesRows() {
        return Math.max(1, Math.min(6, config.getInt("categories.rows", 3)));
    }

    /** The empty slots of the category menu: {@code categories.filler}, else the filler of the board. */
    public String categoriesFiller() {
        String own = config.getString("categories.filler");
        return own == null ? filler() : own.toUpperCase(Locale.ROOT);
    }

    /**
     * The title of the board of a category, from {@code categories.<key>.title}, or null when it is not set and
     * {@code menu.title} is used. %category% in it is the name of the button, without colors.
     */
    public String categoryBoardTitle(Category category) {
        String title = config.getString("categories." + category.key() + ".title");
        return title == null || title.isBlank() ? null : title;
    }

    /** The button of a category, from {@code categories.<key>} with defaults for whatever is missing. */
    public CategoryButton categoryButton(Category category) {
        String[] look = DEFAULT_LOOK.get(category);
        ConfigurationSection section = config.getConfigurationSection("categories." + category.key());
        int slot = 10 + category.ordinal();
        String material = look[0];
        String name = look[1] + category.title();
        List<String> lore = List.of("&#B6C1C8Quests", "", "&fQuests up now: &#FFE75C%active%&f of &#FFE75C%total%", "",
                "&#FF9558\u23F5 &l&nCLICK&r&#FF9558 to open");
        if (section != null) {
            slot = section.getInt("slot", slot);
            material = section.getString("material", material);
            name = section.getString("name", name);
            if (section.isList("lore")) lore = section.getStringList("lore");
        }
        return new CategoryButton(slot, new Quest.Display(material.toUpperCase(Locale.ROOT), name, lore));
    }

    /** The slot of the back button of a board: {@code menu.back-slot}, or the middle of the bottom row. */
    public int backSlot() {
        int slot = config.getInt(menu("back-slot"), -1);
        return slot >= 0 && slot < menuRows * 9 ? slot : menuRows * 9 - 5;
    }

    public Quest.Display backItem() {
        ConfigurationSection section = config.getConfigurationSection(menu("back-item"));
        if (section == null) return new Quest.Display("ARROW", "&#FF9558Back", List.of("&#B6C1C8Quests", "", "&fBack to the categories."));
        return new Quest.Display(section.getString("material", "ARROW").toUpperCase(Locale.ROOT),
                section.getString("name", "&#FF9558Back"), section.getStringList("lore"));
    }

    private static final Map<Category, String[]> DEFAULT_LOOK = Map.of(
            Category.COMBAT, new String[]{"DIAMOND_SWORD", "&#FF6E6E"},
            Category.MINING, new String[]{"DIAMOND_PICKAXE", "&#68C8FF"},
            Category.FARMING, new String[]{"WHEAT", "&#A8E6A3"},
            Category.ANIMALS, new String[]{"BONE", "&#FFE29A"},
            Category.FISHING, new String[]{"FISHING_ROD", "&#74C7FF"},
            Category.EXPLORATION, new String[]{"COMPASS", "&#AAA5FF"},
            Category.MISC, new String[]{"NETHER_STAR", "&#FF8BB0"});

    public boolean broadcast(String which) {
        return config.getBoolean("broadcasts." + which, false);
    }
}
