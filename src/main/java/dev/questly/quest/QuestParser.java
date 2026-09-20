package dev.questly.quest;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/** Reads quests.yml. A quest with a problem is skipped and the reason is logged. */
public final class QuestParser {

    /** Tells whether a name is a real entity, block or item. Tests can use one that accepts everything. */
    public interface Names {
        boolean entity(String name);

        boolean block(String name);

        boolean item(String name);
    }

    private QuestParser() {
    }

    public static List<Quest> parse(@Nullable ConfigurationSection root, Logger log, Names names) {
        List<Quest> quests = new ArrayList<>();
        ConfigurationSection section = root == null ? null : root.getConfigurationSection("quests");
        if (section == null) {
            log.warning("quests.yml has no 'quests' section.");
            return quests;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                log.warning("quests.yml: '" + key + "' is not a quest.");
                continue;
            }
            Quest quest = parseOne(key, entry, log, names);
            if (quest != null) quests.add(quest);
        }
        return quests;
    }

    private static @Nullable Quest parseOne(String id, ConfigurationSection entry, Logger log, Names names) {
        ConfigurationSection settings = entry.getConfigurationSection("settings");
        if (settings == null) {
            log.warning("quests.yml: quest " + id + " has no settings, skipping it.");
            return null;
        }

        Trigger trigger = Trigger.parse(settings.getString("trigger"));
        if (trigger == null) {
            log.warning("quests.yml: quest " + id + " has the trigger '" + settings.getString("trigger")
                    + "', which is not one of " + java.util.Arrays.toString(Trigger.values()) + ". Skipping it.");
            return null;
        }

        String extra = settings.isString("extra") ? settings.getString("extra", "").trim().toUpperCase(Locale.ROOT) : null;
        if (extra != null && extra.isEmpty()) extra = null;
        if (extra != null && !validTarget(trigger, extra, names)) {
            log.warning("quests.yml: quest " + id + " asks for '" + extra + "', which is not a valid "
                    + trigger.target().name().toLowerCase(Locale.ROOT) + " for " + trigger + ". Skipping it.");
            return null;
        }
        if (extra != null && trigger.target() == Trigger.Target.NONE) {
            log.warning("quests.yml: quest " + id + " has an extra, but " + trigger + " does not use one. Ignoring it.");
            extra = null;
        }

        long required = settings.getLong("required", 0);
        if (required < 1) {
            log.warning("quests.yml: quest " + id + " needs a 'required' of at least 1, skipping it.");
            return null;
        }

        ConfigurationSection item = entry.getConfigurationSection("display-item");
        String material = item == null ? "PAPER" : item.getString("material", "PAPER").trim().toUpperCase(Locale.ROOT);
        if (!names.item(material)) {
            log.warning("quests.yml: quest " + id + " shows the item '" + material + "', which does not exist. Skipping it.");
            return null;
        }

        String title = entry.getString("title", "Quest " + id);
        String name = item == null ? "%title%" : item.getString("name", "%title%");
        List<String> lore = item == null ? List.of() : List.copyOf(item.getStringList("lore"));

        double chance = entry.getDouble("chance", 50);
        if (chance <= 0) {
            log.warning("quests.yml: quest " + id + " has a chance of " + chance + " and will never appear.");
        }

        return new Quest(id, title, Math.max(0, chance), trigger, extra, required,
                settings.getBoolean("prevent-cheating", true), Math.max(0, settings.getInt("win-points", 0)),
                new Quest.Display(material, name, lore), parseRewards(settings.getConfigurationSection("rewards")));
    }

    private static boolean validTarget(Trigger trigger, String extra, Names names) {
        return switch (trigger.target()) {
            case ENTITY -> names.entity(extra);
            case BLOCK -> names.block(extra);
            case ITEM -> names.item(extra);
            case NONE -> true;
        };
    }

    private static List<Quest.Reward> parseRewards(@Nullable ConfigurationSection rewards) {
        List<Quest.Reward> result = new ArrayList<>();
        if (rewards == null) return result;
        for (String place : rewards.getKeys(false)) {
            ConfigurationSection entry = rewards.getConfigurationSection(place);
            if (entry == null) continue;
            try {
                result.add(new Quest.Reward(Integer.parseInt(place), List.copyOf(entry.getStringList("commands"))));
            } catch (NumberFormatException ignored) {
                // a reward that is not numbered has no place on the leaderboard
            }
        }
        result.sort(java.util.Comparator.comparingInt(Quest.Reward::place));
        return result;
    }
}
