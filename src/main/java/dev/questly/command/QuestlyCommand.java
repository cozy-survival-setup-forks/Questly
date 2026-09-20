package dev.questly.command;

import dev.questly.QuestlyPlugin;
import dev.questly.board.Board;
import dev.questly.quest.Quest;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /questly opens the board, shop opens the shop, points shows points. For admins: admin points give|take|set,
 * admin setquest, admin reset and admin reload.
 */
public final class QuestlyCommand implements TabExecutor {

    private final QuestlyPlugin plugin;

    public QuestlyCommand(QuestlyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        String sub = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "open" -> open(sender, false);
            case "shop" -> open(sender, true);
            case "points" -> points(sender, args);
            case "admin" -> admin(sender, args);
            default -> plugin.messages().send(sender, "usage");
        }
        return true;
    }

    private void open(CommandSender sender, boolean shop) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
        } else if (!player.hasPermission("questly.use")) {
            plugin.messages().send(sender, "no-permission");
        } else if (shop) {
            plugin.menus().openShop(player);
        } else {
            plugin.menus().openBoard(player);
        }
    }

    private void points(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            if (!sender.hasPermission("questly.points.others")) {
                plugin.messages().send(sender, "no-permission");
                return;
            }
            OfflinePlayer target = find(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "unknown-player");
                return;
            }
            plugin.messages().send(sender, "points-other", Map.of(
                    "%player%", String.valueOf(target.getName()),
                    "%points%", String.valueOf(plugin.service().points().get(target.getUniqueId()))));
            return;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        plugin.messages().send(sender, "points", Map.of("%points%", String.valueOf(plugin.service().points().get(player.getUniqueId()))));
    }

    /** An online player, or one who has played before. Never asks Mojang. */
    private static @Nullable OfflinePlayer find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer known = Bukkit.getOfflinePlayerIfCached(name);
        return known != null && known.hasPlayedBefore() ? known : null;
    }

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("questly.admin")) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        String action = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "reload" -> {
                int amount = plugin.reloadAll();
                plugin.messages().send(sender, "reloaded", Map.of("%amount%", String.valueOf(amount)));
            }
            case "reset" -> {
                plugin.service().reset();
                plugin.messages().send(sender, "reset");
            }
            case "setquest" -> setQuest(sender, args);
            case "points" -> changePoints(sender, args);
            default -> plugin.messages().send(sender, "usage");
        }
    }

    /** /questly admin setquest <slot> <quest id>. The slots are numbered from 1. */
    private void setQuest(CommandSender sender, String[] args) {
        Integer slot = args.length < 3 ? null : parse(args[2]);
        if (slot == null || plugin.service().board().slot(slot - 1) == null) {
            plugin.messages().send(sender, "unknown-slot");
            return;
        }
        Quest quest = args.length < 4 ? null : plugin.quests().get(args[3]);
        if (quest == null) {
            plugin.messages().send(sender, "unknown-quest");
            return;
        }
        plugin.service().board().set(slot - 1, quest);
        plugin.service().saveSlot(slot - 1);
        plugin.messages().send(sender, "quest-set", Map.of("%slot%", String.valueOf(slot), "%quest%", quest.titleText()));
    }

    /** /questly admin points <give|take|set> <player> <amount> */
    private void changePoints(CommandSender sender, String[] args) {
        String mode = args.length < 3 ? "" : args[2].toLowerCase(Locale.ROOT);
        OfflinePlayer target = args.length < 4 ? null : find(args[3]);
        Integer amount = args.length < 5 ? null : parse(args[4]);
        if (!List.of("give", "take", "set").contains(mode) || amount == null || amount < 0) {
            plugin.messages().send(sender, "usage");
            return;
        }
        if (target == null) {
            plugin.messages().send(sender, "unknown-player");
            return;
        }

        var points = plugin.service().points();
        String name = String.valueOf(target.getName());
        switch (mode) {
            case "give" -> points.add(target.getUniqueId(), name, amount);
            case "take" -> points.set(target.getUniqueId(), name, points.get(target.getUniqueId()) - amount);
            default -> points.set(target.getUniqueId(), name, amount);
        }
        plugin.messages().send(sender, "points-changed", Map.of("%player%", name,
                "%points%", String.valueOf(points.get(target.getUniqueId()))));
    }

    private static @Nullable Integer parse(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        List<String> options = new ArrayList<>();
        boolean admin = sender.hasPermission("questly.admin");
        if (args.length == 1) {
            options.addAll(List.of("open", "shop", "points"));
            if (admin) options.add("admin");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("points") && sender.hasPermission("questly.points.others")) {
            Bukkit.getOnlinePlayers().forEach(player -> options.add(player.getName()));
        } else if (args[0].equalsIgnoreCase("admin") && admin) {
            if (args.length == 2) options.addAll(List.of("reload", "reset", "setquest", "points"));
            else if (args[1].equalsIgnoreCase("points")) {
                if (args.length == 3) options.addAll(List.of("give", "take", "set"));
                if (args.length == 4) Bukkit.getOnlinePlayers().forEach(player -> options.add(player.getName()));
            } else if (args[1].equalsIgnoreCase("setquest")) {
                if (args.length == 3) {
                    for (Board.Slot slot : plugin.service().board().slots()) options.add(String.valueOf(slot.index() + 1));
                }
                if (args.length == 4) plugin.quests().all().forEach(quest -> options.add(quest.id()));
            }
        }

        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }
}
