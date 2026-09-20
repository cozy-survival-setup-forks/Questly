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
        if (quest.winPoints() > 0) {
            points.add(completion.winner(), completion.winnerName(), quest.winPoints());
        }
        giveRewards(completion);

        if (plugin.settings().broadcast("completed")) {
            plugin.messages().broadcast("completed", Map.of(
                    "%player%", completion.winnerName(),
                    "%quest%", quest.titleText(),
                    "%points%", String.valueOf(quest.winPoints())));
        }
        saveSlot(completion.slot());
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
        storage.takePending(id).thenAccept(commands -> {
            if (commands.isEmpty()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (String command : commands) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                }
                Player online = Bukkit.getPlayer(id);
                if (online != null) {
                    plugin.messages().send(online, "reward-waiting", Map.of("%amount%", String.valueOf(commands.size())));
                }
            });
        });
    }

    /** New quests for every slot. */
    public List<Board.Rotation> reset() {
        List<Board.Rotation> rotations = board.reset();
        saveAll();
        return rotations;
    }
}
