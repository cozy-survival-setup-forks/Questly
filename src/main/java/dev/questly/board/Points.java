package dev.questly.board;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The quest points of every player. Kept in memory and saved by whoever listens for changes. */
public final class Points {

    /** Told every time a player's points change, so they can be saved. */
    public interface Listener {
        void changed(UUID player, String name, int points);
    }

    private final Map<UUID, Integer> values = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private Listener listener = (player, name, points) -> { };

    public void listen(Listener listener) {
        this.listener = listener;
    }

    /** Puts saved points back without telling anybody. */
    public void load(UUID player, String name, int points) {
        values.put(player, points);
        names.put(player, name);
    }

    public int get(UUID player) {
        return values.getOrDefault(player, 0);
    }

    public void remember(UUID player, String name) {
        if (name != null) names.put(player, name);
    }

    public String nameOf(UUID player) {
        return names.getOrDefault(player, "");
    }

    public void set(UUID player, String name, int amount) {
        remember(player, name);
        int value = Math.max(0, amount);
        values.put(player, value);
        listener.changed(player, nameOf(player), value);
    }

    public void add(UUID player, String name, int amount) {
        set(player, name, (int) Math.min(Integer.MAX_VALUE, (long) get(player) + amount));
    }

    /** Takes points if the player has that many. The check and the taking are one step, so it cannot be spent twice. */
    public boolean spend(UUID player, String name, int amount) {
        if (amount < 0 || get(player) < amount) return false;
        set(player, name, get(player) - amount);
        return true;
    }
}
