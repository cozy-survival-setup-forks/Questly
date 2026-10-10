package dev.questly;

import dev.questly.board.Board;
import dev.questly.board.Points;
import dev.questly.quest.Quest;
import dev.questly.quest.Trigger;
import dev.questly.storage.SqliteStorage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Connects the board to the server: counts what players do, gives out the points and rewards, keeps everything saved.
 * Only the main thread uses this.
 */
public final class BoardService {

    private final QuestlyPlugin plugin;
    private final Board board;
    private final Points points;
    private final SqliteStorage storage;

    BoardService(QuestlyPlugin plugin, Board board, Points points, SqliteStorage storage) {
        this.plugin = plugin;
        this.board = board;
        this.points = points;
        this.storage = storage;
        points.listen(storage::savePoints);
    }

    public Board board() {
        return board;
    }

    public Points points() {
        return points;
    }

    /** Counts something a player did. Cheap when no quest on the board asks for it. */
    public void progress(Player player, Trigger trigger, @Nullable String value, double amount, boolean legit) {
        if (!board.wants(trigger)) return;
        if (player.hasMetadata("NPC")) return;
        GameMode mode = player.getGameMode();
        if (!plugin.settings().countCreative() && mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return;

        handle(board.record(player.getUniqueId(), player.getName(), trigger, value, amount, legit));
    }

    private void handle(Board.Result result) {
        for (Board.Completion completion : result.completions()) {
            complete(completion);
        }
        for (Board.Takeover takeover : result.takeovers()) {
            Player leader = Bukkit.getPlayer(takeover.previousLeader());
            if (leader != null && plugin.settings().broadcast("taken-over")) {
                plugin.messages().send(leader, "taken-over", Map.of(
                        "%player%", takeover.newLeaderName(), "%quest%", takeover.quest().titleText()));
            }
        }
    }

    private void complete(Board.Completion completion) {
        Quest quest = completion.quest();
        // written down, and the finished board saved, before anything is paid: a stop in the middle can then never pay
        // the same quest twice, and rewards that may have been missed are listed for a person to check
        String record = storage.begin("quest-reward", "quest=" + quest.titleText() + " winner=" + completion.winnerName()
                + " (" + completion.winner() + ")");
        if (quest.winPoints() > 0) {
            points.add(completion.winner(), completion.winnerName(), quest.winPoints());
        }
        boolean saved = saveSlotNow(completion.slot());
        if (saved) {
            giveRewards(completion);
            storage.finish(record, true, null);
        } else {
            storage.flag(record, "the finished board could not be saved, so the rewards were not paid");
            plugin.getLogger().severe("The finished quest of " + completion.winnerName() + " could not be saved, so its rewards were not paid. "
                    + "They are listed in /questly doctor.");
        }

        if (plugin.settings().broadcast("completed")) {
            plugin.messages().broadcast("completed", Map.of(
                    "%player%", completion.winnerName(),
                    "%quest%", quest.titleText(),
                    "%points%", String.valueOf(quest.winPoints())));
        }
    }

    /** Runs the reward commands for each place of the leaderboard that has some. Players who are away get them later. */
    private void giveRewards(Board.Completion completion) {
        for (Quest.Reward reward : completion.quest().rewards()) {
            int index = reward.place() - 1;
            if (index < 0 || index >= completion.ranking().size()) continue;

            Board.Entry entry = completion.ranking().get(index);
            for (String command : reward.commands()) {
                String line = command.replace("%player%", entry.name());
                if (Bukkit.getPlayer(entry.id()) != null) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
                } else {
                    storage.addPending(entry.id(), line);
                }
            }
        }
    }

    /** Runs once a second: gives new quests to the slots that rested long enough. */
    void tick() {
        for (Board.Rotation rotation : board.tick()) {
            saveSlot(rotation.slot());
            if (plugin.settings().broadcast("new-quest")) {
                plugin.messages().broadcast("new-quest", Map.of("%quest%", rotation.quest().titleText()));
            }
        }
    }

    public void saveSlot(int index) {
        Board.Slot slot = board.slot(index);
        if (slot == null || slot.quest() == null) return;
        storage.saveSlot(board.snapshot(slot));
        slot.clean();
    }

    /** Saves a slot and waits until it is on disk. @return false when it could not be written */
    boolean saveSlotNow(int index) {
        Board.Slot slot = board.slot(index);
        if (slot == null || slot.quest() == null) return true;
        boolean written = storage.saveSlotNow(board.snapshot(slot));
        if (written) slot.clean();
        return written;
    }

    /** For the shop: the record of a payout, written before it is made. Null when it could not be written. */
    public String beginPayout(String kind, String detail) {
        return storage.begin(kind, detail);
    }

    public void finishPayout(String record, boolean ok, String reason) {
        storage.finish(record, ok, reason);
    }

    /** Waits until the points that were just changed are on disk. @return false when they could not be written */
    public boolean flushStorage() {
        return storage.flush();
    }

    /** Saves the progress of every slot that changed since the last time. */
    void saveDirty() {
        for (Board.Slot slot : board.slots()) {
            if (slot.dirty()) saveSlot(slot.index());
        }
    }

    void saveAll() {
        for (Board.Slot slot : board.slots()) {
            saveSlot(slot.index());
        }
    }

    /** Gives a player who just joined the rewards they won while away. */
    void deliverPending(Player player) {
        UUID id = player.getUniqueId();
        points.remember(id, player.getName());
        storage.takePending(id).thenAccept(claimed -> {
            List<String> commands = claimed.commands();
            if (commands.isEmpty()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(id);
                if (online == null) {
                    // Quit in the gap between the database read and this tick running. The rows are
                    // already deleted from storage (takePending took them), so put them back instead
                    // of dispatching commands against nobody and losing the reward for good.
                    for (String command : commands) storage.addPending(id, command);
                    storage.finish(claimed.record(), false, "put back in the queue");
                    return;
                }
                for (String command : commands) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                }
                storage.finish(claimed.record(), true, null);
                plugin.messages().send(online, "reward-waiting", Map.of("%amount%", String.valueOf(commands.size())));
            });
        }).exceptionally(e -> {
            // Without this, a failure here (e.g. the plugin disabling right as this runs, which makes
            // runTask throw) is swallowed by the future with no trace, and the pending rows are
            // already gone from the database - the rewards would just vanish silently.
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to deliver pending rewards for " + id, e);
            return null;
        });
    }

    /** New quests for every slot. */
    public List<Board.Rotation> reset() {
        List<Board.Rotation> rotations = board.reset();
        saveAll();
        return rotations;
    }
}
