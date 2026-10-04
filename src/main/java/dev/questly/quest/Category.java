package dev.questly.quest;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/** The groups the quest board is split into. A quest names its own in quests.yml, or gets one from what it asks for. */
public enum Category {
    COMBAT("combat", "Combat Quests"),
    MINING("mining", "Mining Quests"),
    FARMING("farming", "Farming Quests"),
    ANIMALS("animals", "Animal Quests"),
    FISHING("fishing", "Fishing Quests"),
    EXPLORATION("exploration", "Exploration Quests"),
    MISC("misc", "Miscellaneous Quests");

    /** Mobs that count as animals: killing one is an animal quest, not a combat one. */
    private static final Set<String> PASSIVE = Set.of("COW", "MOOSHROOM", "PIG", "SHEEP", "CHICKEN", "RABBIT", "HORSE",
            "DONKEY", "MULE", "LLAMA", "TRADER_LLAMA", "CAT", "OCELOT", "WOLF", "PARROT", "FOX", "BEE", "TURTLE", "GOAT",
            "FROG", "CAMEL", "ARMADILLO", "SNIFFER", "AXOLOTL", "PANDA", "POLAR_BEAR", "SQUID", "GLOW_SQUID", "DOLPHIN",
            "COD", "SALMON", "PUFFERFISH", "TROPICAL_FISH", "BAT", "STRIDER", "ALLAY");

    /** Plants and trees: breaking them is farming rather than mining. */
    private static final Set<String> CROPS = Set.of("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART", "MELON",
            "PUMPKIN", "SUGAR_CANE", "COCOA", "SWEET_BERRY_BUSH", "CACTUS", "BAMBOO", "KELP", "KELP_PLANT", "TORCHFLOWER",
            "PITCHER_CROP", "CAVE_VINES", "CAVE_VINES_PLANT", "BROWN_MUSHROOM", "RED_MUSHROOM", "HAY_BLOCK");

    private final String key;
    private final String title;

    Category(String key, String title) {
        this.key = key;
        this.title = title;
    }

    /** The name used in config.yml and quests.yml, such as {@code combat}. */
    public String key() {
        return key;
    }

    /** The name shown when config.yml does not set one. */
    public String title() {
        return title;
    }

    public static @Nullable Category parse(@Nullable String name) {
        if (name == null) return null;
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (Category category : values()) {
            if (category.key.equals(wanted) || category.name().equalsIgnoreCase(wanted)) return category;
        }
        return null;
    }

    /** The category a quest belongs to when quests.yml does not say. */
    public static Category infer(Trigger trigger, @Nullable String extra) {
        return switch (trigger) {
            case KILL -> extra != null && PASSIVE.contains(extra) ? ANIMALS : COMBAT;
            case BREAK -> extra != null && (CROPS.contains(extra) || extra.endsWith("_LOG") || extra.endsWith("_STEM")
                    || extra.endsWith("_WOOD")) ? FARMING : MINING;
            case BREED_ENTITY, TAME_ENTITY -> ANIMALS;
            case FISH_CAUGHT -> FISHING;
            case WALK, SPRINT, SWIM, AVIATE, RIDE_VEHICLE -> EXPLORATION;
            case CHAT, ENCHANT_ITEM -> MISC;
        };
    }
}
