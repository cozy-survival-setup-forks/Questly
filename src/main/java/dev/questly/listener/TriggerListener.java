package dev.questly.listener;

import dev.questly.BoardService;
import dev.questly.QuestlyPlugin;
import dev.questly.quest.Trigger;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns what happens on the server into quest progress. Everything is counted after other plugins had their say, so an
 * action that was cancelled does not count, and it always runs on the main thread.
 */
public final class TriggerListener implements Listener {

    /** Crops that only count when they are ripe, whoever planted them. */
    private static final Set<Material> CROPS = Set.of(Material.WHEAT, Material.CARROTS, Material.POTATOES,
            Material.BEETROOTS, Material.NETHER_WART, Material.COCOA, Material.TORCHFLOWER_CROP,
            Material.PITCHER_CROP, Material.SWEET_BERRY_BUSH);

    private final QuestlyPlugin plugin;
    private final BoardService service;
    private final PlacedBlocks placed;
    private final NamespacedKey farmed;
    private final Map<UUID, Long> lastChat = new HashMap<>();
    private final Map<UUID, String> lastText = new HashMap<>();

    public TriggerListener(QuestlyPlugin plugin, BoardService service, PlacedBlocks placed) {
        this.plugin = plugin;
        this.service = service;
        this.placed = placed;
        this.farmed = new NamespacedKey(plugin, "farmed");
    }

    // Mobs that came out of a spawner or a spawn egg are marked, killing them gives no progress.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        switch (event.getSpawnReason()) {
            case SPAWNER, SPAWNER_EGG, DISPENSE_EGG ->
                    event.getEntity().getPersistentDataContainer().set(farmed, PersistentDataType.BYTE, (byte) 1);
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        boolean legit = !event.getEntity().getPersistentDataContainer().has(farmed, PersistentDataType.BYTE);
        service.progress(killer, Trigger.KILL, entityName(event.getEntity()), 1, legit);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        placed.mark(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();

        boolean legit;
        if (CROPS.contains(type) && block.getBlockData() instanceof Ageable crop) {
            legit = crop.getAge() >= crop.getMaximumAge();
        } else {
            legit = !placed.isPlaced(block);
        }
        placed.unmark(block);

        service.progress(event.getPlayer(), Trigger.BREAK, type.name(), 1, legit);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player) {
            service.progress(player, Trigger.BREED_ENTITY, entityName(event.getEntity()), 1, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (event.getOwner() instanceof Player player) {
            service.progress(player, Trigger.TAME_ENTITY, entityName(event.getEntity()), 1, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (event.getCaught() instanceof Item item) {
            service.progress(event.getPlayer(), Trigger.FISH_CAUGHT, item.getItemStack().getType().name(), 1, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        service.progress(event.getEnchanter(), Trigger.ENCHANT_ITEM, null, 1, true);
    }

    /** Chat is written off the main thread, so the message is handed over and counted there. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> countChat(player, text));
    }

    private void countChat(Player player, String text) {
        if (!player.isOnline() || !service.board().wants(Trigger.CHAT)) return;

        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        long last = lastChat.getOrDefault(id, 0L);
        boolean longEnough = text.length() >= plugin.settings().chatMinLength();
        boolean slowEnough = now - last >= plugin.settings().chatCooldownMillis();
        boolean different = !text.equalsIgnoreCase(lastText.getOrDefault(id, ""));

        boolean legit = longEnough && slowEnough && different;
        if (legit) {
            lastChat.put(id, now);
            lastText.put(id, text);
        }
        service.progress(player, Trigger.CHAT, null, 1, legit);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastChat.remove(event.getPlayer().getUniqueId());
        lastText.remove(event.getPlayer().getUniqueId());
    }

    private static String entityName(Entity entity) {
        return entity.getType().getKey().getKey().toUpperCase(Locale.ROOT);
    }
}
