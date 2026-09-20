package dev.questly.quest;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** All the quests, in the order of the file, and the picking of a random one. */
public final class QuestLibrary {

    private final Map<String, Quest> byId = new LinkedHashMap<>();

    public QuestLibrary(List<Quest> quests) {
        for (Quest quest : quests) {
            byId.putIfAbsent(quest.id(), quest);
        }
    }

    public @Nullable Quest get(@Nullable String id) {
        return id == null ? null : byId.get(id);
    }

    public Collection<Quest> all() {
        return byId.values();
    }

    public int size() {
        return byId.size();
    }

    /**
     * A random quest, more likely the higher its chance. Quests already on the board are left out, and so are
     * quests that ask for the same thing as one on the board, unless nothing else is left.
     */
    public @Nullable Quest pick(Random random, Collection<Quest> onBoard, boolean unique) {
        if (byId.isEmpty()) return null;
        if (unique) {
            Quest quest = weighted(random, byId.values().stream()
                    .filter(candidate -> onBoard.stream().noneMatch(taken -> taken.id().equals(candidate.id()) || taken.sameGoal(candidate)))
                    .toList());
            if (quest != null) return quest;
            quest = weighted(random, byId.values().stream()
                    .filter(candidate -> onBoard.stream().noneMatch(taken -> taken.id().equals(candidate.id())))
                    .toList());
            if (quest != null) return quest;
        }
        return weighted(random, List.copyOf(byId.values()));
    }

    private static @Nullable Quest weighted(Random random, List<Quest> candidates) {
        double total = 0;
        for (Quest quest : candidates) total += quest.chance();
        if (candidates.isEmpty()) return null;
        if (total <= 0) return candidates.get(random.nextInt(candidates.size()));

        double roll = random.nextDouble() * total;
        for (Quest quest : candidates) {
            roll -= quest.chance();
            if (roll < 0) return quest;
        }
        return candidates.get(candidates.size() - 1);
    }
}
