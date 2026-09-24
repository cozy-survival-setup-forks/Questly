package dev.questly;

import dev.questly.quest.Quest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;

/** The values of config.yml. */
public final class Settings {

    private final FileConfiguration config;

    Settings(FileConfiguration config) {
        this.config = config;
    }

    public int slots() {
        return Math.max(1, Math.min(54, config.getInt("board.slots", 9)));
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

    public String guiTitle() {
        return config.getString("gui.title", "Quest Board");
    }

    /** Rows of the quest board, or 0 to fit the quests with a filler row above and below. */
    public int guiRows() {
        return Math.max(0, Math.min(6, config.getInt("gui.rows", 0)));
    }

    /** The slots the quests are shown in, or an empty list to use the rows in the middle. */
    public java.util.List<Integer> guiQuestSlots() {
        return config.getIntegerList("gui.quest-slots");
    }

    public String filler() {
        return config.getString("gui.filler", "BLACK_STAINED_GLASS_PANE").toUpperCase(Locale.ROOT);
    }

    /** What is shown where a quest was won and the next one has not started. */
    public Quest.Display cooldownItem() {
        ConfigurationSection section = config.getConfigurationSection("gui.cooldown-item");
        if (section == null) return new Quest.Display("CLOCK", "Next quest in %time%", List.of());
        return new Quest.Display(section.getString("material", "CLOCK").toUpperCase(Locale.ROOT),
                section.getString("name", "Next quest in %time%"), section.getStringList("lore"));
    }

    public boolean broadcast(String which) {
        return config.getBoolean("broadcasts." + which, false);
    }
}
