package dev.questly.hook;

import dev.questly.QuestlyPlugin;
import dev.questly.board.Board;
import dev.questly.quest.Quest;
import dev.questly.util.Duration;
import dev.questly.util.Text;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * %questly_points%, and for every place of the board (numbered from 1) %questly_1_title%, _required, _points,
 * _score, _time, _status, _top_1_name and _top_1_score.
 */
public final class QuestlyExpansion extends PlaceholderExpansion {

    // Capped at 3 digits so Integer.parseInt below can never overflow - %questly_99999999999_title%
    // (from a malformed menu placeholder elsewhere) used to throw NumberFormatException into
    // whatever plugin resolved it, instead of this expansion just answering "not one of ours".
    private static final Pattern SLOT = Pattern.compile("(\\d{1,3})_(.+)");
    private static final Pattern TOP = Pattern.compile("top_(\\d{1,3})_(name|score)");

    private final QuestlyPlugin plugin;

    public QuestlyExpansion(QuestlyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "questly";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Questly";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @NotNull String getRequiredPlugin() {
        // Without this, PlaceholderAPI has no reason to unregister the expansion when Questly is
        // disabled (by a plugin manager or a reload), so it keeps serving from the old classloader.
        return "Questly";
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";
        String request = params.toLowerCase(Locale.ROOT);
        if (request.equals("points")) return String.valueOf(plugin.service().points().get(player.getUniqueId()));

        try {
            return boardPlaceholder(player, request);
        } catch (java.util.ConcurrentModificationException e) {
            // Board is documented main-thread-only, but scoreboard/TAB plugins commonly resolve
            // PlaceholderAPI placeholders from an async thread. A torn read here is rare and this
            // placeholder refreshes constantly, so skipping one tick's value beats crashing the
            // caller's async task.
            return "";
        }
    }

    private @Nullable String boardPlaceholder(Player player, String request) {
        Matcher slotMatch = SLOT.matcher(request);
        if (!slotMatch.matches()) return null;
        Board.Slot slot = plugin.service().board().slot(Integer.parseInt(slotMatch.group(1)) - 1);
        if (slot == null) return "";
        Quest quest = slot.quest();
        if (quest == null) return "";

        String what = slotMatch.group(2);
        switch (what) {
            case "title" -> {
                return quest.titleText();
            }
            case "required" -> {
                return String.valueOf(quest.required());
            }
            case "points" -> {
                return String.valueOf(quest.winPoints());
            }
            case "score" -> {
                return Text.number(Math.floor(slot.scoreOf(player.getUniqueId())));
            }
            case "time" -> {
                return Duration.clock(plugin.service().board().restLeft(slot));
            }
            case "status" -> {
                return slot.active() ? "active" : "resting";
            }
            default -> {
                Matcher top = TOP.matcher(what);
                if (!top.matches()) return null;
                List<Board.Entry> ranking = slot.ranking();
                int place = Integer.parseInt(top.group(1));
                Board.Entry entry = place >= 1 && place <= ranking.size() ? ranking.get(place - 1) : null;
                if (top.group(2).equals("name")) return entry == null ? "---" : entry.name();
                return entry == null ? "0" : Text.number(Math.floor(entry.score()));
            }
        }
    }
}
