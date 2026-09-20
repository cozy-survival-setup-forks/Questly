package dev.questly.quest;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One quest of quests.yml.
 *
 * @param extra the entity, block or item the quest is about, or null for any
 */
public record Quest(String id, String title, double chance, Trigger trigger, @Nullable String extra, long required,
                    boolean preventCheating, int winPoints, Display display, List<Reward> rewards) {

    /** The item the quest shows on the board. */
    public record Display(String material, String name, List<String> lore) {
    }

    /** Commands for the player who finished at this place of the leaderboard. */
    public record Reward(int place, List<String> commands) {
    }

    /** What a caught item must be for a fishing quest that names none. */
    private static final Set<String> FISH = Set.of("COD", "SALMON", "PUFFERFISH", "TROPICAL_FISH");

    /** The title with the amount filled in. */
    public String titleText() {
        return title.replace("%required%", String.valueOf(required));
    }

    /**
     * Whether an action counts for this quest. {@code value} is the name of the entity, block or item involved.
     * A quest about an ore also takes the deepslate version of it, and a fishing quest without a target takes
     * any fish but not junk.
     */
    public boolean matches(@Nullable String value) {
        if (extra == null) {
            return trigger != Trigger.FISH_CAUGHT || (value != null && FISH.contains(value.toUpperCase(Locale.ROOT)));
        }
        if (value == null) return false;
        if (extra.equalsIgnoreCase(value)) return true;
        return trigger == Trigger.BREAK && isOre(extra) && value.equalsIgnoreCase("DEEPSLATE_" + extra);
    }

    private static boolean isOre(String block) {
        return block.endsWith("_ORE") && !block.startsWith("NETHER_") && !block.startsWith("DEEPSLATE_");
    }

    /** A quest that asks for the same thing as another one, for keeping the board varied. */
    public boolean sameGoal(Quest other) {
        return trigger == other.trigger && java.util.Objects.equals(extra, other.extra);
    }
}
