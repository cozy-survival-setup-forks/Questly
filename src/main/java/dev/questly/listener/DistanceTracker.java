package dev.questly.listener;

import dev.questly.BoardService;
import dev.questly.quest.Trigger;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Counts the blocks players travel, once a second, from the statistics the game keeps. A jump of more than a player can
 * really travel in a second, such as a teleport or a glitch, is not counted for quests that prevent cheating.
 */
public final class DistanceTracker implements Listener {

    /** The statistics of a player, in centimetres. */
    private record Reading(long walk, long sprint, long swim, long aviate, long boat, long minecart) {

        static Reading of(Player player) {
            return new Reading(
                    player.getStatistic(Statistic.WALK_ONE_CM),
                    player.getStatistic(Statistic.SPRINT_ONE_CM),
                    player.getStatistic(Statistic.SWIM_ONE_CM),
                    player.getStatistic(Statistic.AVIATE_ONE_CM),
                    player.getStatistic(Statistic.BOAT_ONE_CM),
                    player.getStatistic(Statistic.MINECART_ONE_CM));
        }
    }

    /** The most blocks a player can really cover in one second, on foot, swimming, gliding and in a vehicle. */
    private static final double MAX_WALK = 10;
    private static final double MAX_SPRINT = 12;
    private static final double MAX_SWIM = 10;
    private static final double MAX_AVIATE = 120;
    private static final double MAX_RIDE = 100;

    private final BoardService service;
    private final Map<UUID, Reading> last = new HashMap<>();

    public DistanceTracker(BoardService service) {
        this.service = service;
    }

    public void start(org.bukkit.plugin.Plugin plugin) {
        for (Player player : Bukkit.getOnlinePlayers()) last.put(player.getUniqueId(), Reading.of(player));
        Bukkit.getScheduler().runTaskTimer(plugin, this::sample, 20L, 20L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        last.put(event.getPlayer().getUniqueId(), Reading.of(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        last.remove(event.getPlayer().getUniqueId());
    }

    private void sample() {
        boolean any = false;
        for (Trigger trigger : Trigger.values()) {
            if (trigger.measuresDistance() && service.board().wants(trigger)) any = true;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            Reading now = Reading.of(player);
            Reading before = last.put(player.getUniqueId(), now);
            if (!any || before == null) continue;

            count(player, Trigger.WALK, now.walk() - before.walk(), MAX_WALK, null);
            count(player, Trigger.SPRINT, now.sprint() - before.sprint(), MAX_SPRINT, null);
            count(player, Trigger.SWIM, now.swim() - before.swim(), MAX_SWIM, null);
            count(player, Trigger.AVIATE, now.aviate() - before.aviate(), MAX_AVIATE, null);

            Entity vehicle = player.getVehicle();
            String vehicleName = vehicle == null ? null : vehicle.getType().getKey().getKey().toUpperCase(Locale.ROOT);
            long ridden = (now.boat() - before.boat()) + (now.minecart() - before.minecart());
            count(player, Trigger.RIDE_VEHICLE, ridden, MAX_RIDE, vehicleName);
        }
    }

    private void count(Player player, Trigger trigger, long centimetres, double limit, String value) {
        if (centimetres <= 0 || !service.board().wants(trigger)) return;
        double blocks = centimetres / 100.0;
        service.progress(player, trigger, value, blocks, blocks <= limit);
    }
}
