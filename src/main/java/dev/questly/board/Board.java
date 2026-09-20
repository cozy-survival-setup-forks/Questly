package dev.questly.board;

import dev.questly.quest.Quest;
import dev.questly.quest.QuestLibrary;
import dev.questly.quest.Trigger;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The quests that are up right now and how far everybody is. The first player to reach the amount a quest asks
 * for wins it, then the slot rests for a while and a new quest takes its place. Only the main thread uses this.
 */
public final class Board {

    /** How far one player is on a quest. */
    public static final class Entry {
        private final UUID id;
        private String name;
        private double score;

        Entry(UUID id, String name, double score) {
            this.id = id;
            this.name = name;
            this.score = score;
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        public double score() {
            return score;
        }
    }

    /** One place on the board. */
    public static final class Slot {
        private final int index;
        private @Nullable Quest quest;
        private boolean active = true;
        private long startedAt;
        private final Map<UUID, Entry> scores = new LinkedHashMap<>();
        private boolean dirty;

        Slot(int index) {
            this.index = index;
        }

        public int index() {
            return index;
        }

        public @Nullable Quest quest() {
            return quest;
        }

        /** False while the slot rests after somebody won. */
        public boolean active() {
            return active;
        }

        public long startedAt() {
            return startedAt;
        }

        public boolean dirty() {
            return dirty;
        }

        public void clean() {
            dirty = false;
        }

        public double scoreOf(UUID player) {
            Entry entry = scores.get(player);
            return entry == null ? 0 : entry.score;
        }

        /** Everybody who took part, the best first. */
        public List<Entry> ranking() {
            List<Entry> list = new ArrayList<>(scores.values());
            list.sort(Comparator.comparingDouble((Entry entry) -> entry.score).reversed());
            return list;
        }
    }

    /** A quest that was just won. {@code ranking} is the leaderboard at that moment. */
    public record Completion(int slot, Quest quest, UUID winner, String winnerName, List<Entry> ranking) {
    }

    /** A slot that got a new quest. */
    public record Rotation(int slot, Quest quest) {
    }

    /** Somebody passed the leader of a quest. */
    public record Takeover(int slot, Quest quest, UUID previousLeader, String newLeaderName) {
    }

    public record Result(List<Completion> completions, List<Takeover> takeovers) {
        static final Result NOTHING = new Result(List.of(), List.of());
    }

    /** What is saved of a slot. */
    public record Saved(int slot, String questId, boolean active, long startedAt, List<Score> scores) {
    }

    public record Score(UUID player, String name, double score) {
    }

    private QuestLibrary library;
    private final Random random;
    private final LongSupplier clock;
    private final List<Slot> slots = new ArrayList<>();
    private boolean unique = true;
    private long cooldownMillis = 1_800_000L;

    public Board(QuestLibrary library, Random random, LongSupplier clock) {
        this.library = library;
        this.random = random;
        this.clock = clock;
    }

    /** Switches to reloaded quests. Slots keep their progress when their quest still exists, otherwise they get a new one. */
    public void setLibrary(QuestLibrary library) {
        this.library = library;
        for (Slot slot : slots) {
            if (slot.quest != null) {
                slot.quest = library.get(slot.quest.id());
                if (slot.quest == null) slot.scores.clear();
            }
        }
    }

    public void configure(int slotCount, long cooldownSeconds, boolean unique) {
        this.cooldownMillis = Math.max(0, cooldownSeconds) * 1000L;
        this.unique = unique;
        while (slots.size() < slotCount) slots.add(new Slot(slots.size()));
        while (slots.size() > slotCount) slots.remove(slots.size() - 1);
    }

    public List<Slot> slots() {
        return slots;
    }

    public @Nullable Slot slot(int index) {
        return index >= 0 && index < slots.size() ? slots.get(index) : null;
    }

    /** Puts the saved slots back. Slots with a quest that no longer exists get a new one. */
    public void restore(List<Saved> saved) {
        for (Saved state : saved) {
            Slot slot = slot(state.slot());
            Quest quest = library.get(state.questId());
            if (slot == null || quest == null) continue;
            slot.quest = quest;
            slot.active = state.active();
            slot.startedAt = state.startedAt();
            slot.scores.clear();
            for (Score score : state.scores()) {
                slot.scores.put(score.player(), new Entry(score.player(), score.name(), score.score()));
            }
        }
        fill();
    }

    public Saved snapshot(Slot slot) {
        List<Score> scores = new ArrayList<>();
        for (Entry entry : slot.scores.values()) scores.add(new Score(entry.id, entry.name, entry.score));
        return new Saved(slot.index, slot.quest == null ? "" : slot.quest.id(), slot.active, slot.startedAt, scores);
    }

    /** Gives a quest to every slot that has none. */
    public List<Rotation> fill() {
        List<Rotation> filled = new ArrayList<>();
        for (Slot slot : slots) {
            if (slot.quest == null) {
                Quest quest = pickFor(slot);
                if (quest != null) {
                    start(slot, quest);
                    filled.add(new Rotation(slot.index, quest));
                }
            }
        }
        return filled;
    }

    private @Nullable Quest pickFor(Slot except) {
        List<Quest> onBoard = new ArrayList<>();
        for (Slot slot : slots) {
            if (slot != except && slot.quest != null) onBoard.add(slot.quest);
        }
        return library.pick(random, onBoard, unique);
    }

    private void start(Slot slot, Quest quest) {
        slot.quest = quest;
        slot.active = true;
        slot.startedAt = clock.getAsLong();
        slot.scores.clear();
        slot.dirty = true;
    }

    /** Replaces the quest of a slot right away, for admins. */
    public boolean set(int index, Quest quest) {
        Slot slot = slot(index);
        if (slot == null) return false;
        start(slot, quest);
        return true;
    }

    /** New quests everywhere, and everybody's progress is gone. */
    public List<Rotation> reset() {
        List<Rotation> rotations = new ArrayList<>();
        for (Slot slot : slots) {
            slot.quest = null;
        }
        for (Slot slot : slots) {
            Quest quest = pickFor(slot);
            if (quest != null) {
                start(slot, quest);
                rotations.add(new Rotation(slot.index, quest));
            }
        }
        return rotations;
    }

    /** True if some quest that is up asks for this trigger. Lets the callers skip the work when nobody cares. */
    public boolean wants(Trigger trigger) {
        for (Slot slot : slots) {
            if (slot.active && slot.quest != null && slot.quest.trigger() == trigger) return true;
        }
        return false;
    }

    /**
     * Counts something a player did.
     *
     * @param value the entity, block or item involved, if any
     * @param legit false when the action looks like cheating, such as breaking a block a player placed. Quests
     *              that prevent cheating do not count it.
     */
    public Result record(UUID player, String name, Trigger trigger, @Nullable String value, double amount, boolean legit) {
        if (amount <= 0) return Result.NOTHING;

        List<Completion> completions = null;
        List<Takeover> takeovers = null;
        for (Slot slot : slots) {
            Quest quest = slot.quest;
            if (!slot.active || quest == null || quest.trigger() != trigger || !quest.matches(value)) continue;
            if (!legit && quest.preventCheating()) continue;

            Entry leaderBefore = leader(slot);
            Entry entry = slot.scores.computeIfAbsent(player, id -> new Entry(id, name, 0));
            entry.name = name;
            entry.score += amount;
            slot.dirty = true;

            if (entry.score >= quest.required()) {
                slot.active = false;
                slot.startedAt = clock.getAsLong();
                if (completions == null) completions = new ArrayList<>();
                completions.add(new Completion(slot.index, quest, player, name, slot.ranking()));
            } else if (leaderBefore != null && !leaderBefore.id.equals(player) && entry.score > leaderBefore.score
                    && leader(slot) == entry) {
                if (takeovers == null) takeovers = new ArrayList<>();
                takeovers.add(new Takeover(slot.index, quest, leaderBefore.id, name));
            }
        }
        if (completions == null && takeovers == null) return Result.NOTHING;
        return new Result(completions == null ? List.of() : completions, takeovers == null ? List.of() : takeovers);
    }

    private static @Nullable Entry leader(Slot slot) {
        Entry best = null;
        for (Entry entry : slot.scores.values()) {
            if (best == null || entry.score > best.score) best = entry;
        }
        return best;
    }

    /** Gives new quests to the slots whose rest is over. */
    public List<Rotation> tick() {
        long now = clock.getAsLong();
        List<Rotation> rotations = new ArrayList<>();
        for (Slot slot : slots) {
            if (slot.quest == null || (!slot.active && now - slot.startedAt >= cooldownMillis)) {
                Quest quest = pickFor(slot);
                if (quest != null) {
                    start(slot, quest);
                    rotations.add(new Rotation(slot.index, quest));
                }
            }
        }
        return rotations;
    }

    /** Milliseconds until a resting slot gets its new quest. */
    public long restLeft(Slot slot) {
        if (slot.active) return 0;
        return Math.max(0, slot.startedAt + cooldownMillis - clock.getAsLong());
    }
}
