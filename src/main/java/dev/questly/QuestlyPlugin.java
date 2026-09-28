package dev.questly;

import dev.questly.board.Board;
import dev.questly.board.Points;
import dev.questly.command.QuestlyCommand;
import dev.questly.gui.Menus;
import dev.questly.hook.QuestlyExpansion;
import dev.questly.listener.DistanceTracker;
import dev.questly.listener.PlacedBlocks;
import dev.questly.listener.TriggerListener;
import dev.questly.quest.QuestLibrary;
import dev.questly.quest.QuestParser;
import dev.questly.storage.SqliteStorage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.logging.Level;

/**
 * Questly: a board of quests that players race to finish. The quests are in quests.yml, and everything is
 * kept in a small file database.
 */
public final class QuestlyPlugin extends JavaPlugin implements Listener {

    private Settings settings;
    private Messages messages;
    private QuestLibrary quests;
    private SqliteStorage storage;
    private BoardService service;
    private PlacedBlocks placed;
    private Menus menus;
    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        try {
            enableInner();
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Questly could not start, check config.yml, quests.yml, shop.yml and lang.yml for mistakes", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void enableInner() {
        saveDefaultConfig();
        settings = new Settings(getConfig());
        messages = new Messages(this);
        messages.load();
        try {
            quests = loadQuests();
        } catch (InvalidConfigurationException | IOException e) {
            getLogger().log(Level.SEVERE, "quests.yml is broken, the board will stay empty until it's fixed and reloaded", e);
            quests = new QuestLibrary(List.of());
        }
        if (quests.size() == 0) {
            getLogger().warning("There are no quests in quests.yml, the board will stay empty.");
        } else {
            getLogger().info("Loaded " + quests.size() + " quests.");
        }

        Board board = new Board(quests, new Random(), System::currentTimeMillis);
        board.configure(settings.slots(), settings.cooldownSeconds(), settings.uniqueQuests());
        Points points = new Points();

        storage = new SqliteStorage(new File(getDataFolder(), "data.db"), getLogger());
        try {
            SqliteStorage.Loaded loaded = storage.open();
            loaded.points().forEach(saved -> points.load(saved.id(), saved.name(), saved.points()));
            board.restore(loaded.slots());
            // If slots: was ever shrunk without a matching cleanup (an older version of this plugin
            // didn't do one), drop whatever's left past the current count now, before it can come
            // back to life on some future restart where slots: is raised again.
            storage.deleteSlotsFrom(settings.slots());
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Could not open data.db, disabling Questly", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        service = new BoardService(this, board, points, storage);
        service.saveDirty();
        placed = new PlacedBlocks(this);
        menus = new Menus(this, service);
        menus.loadShop();

        var manager = getServer().getPluginManager();
        manager.registerEvents(this, this);
        manager.registerEvents(placed, this);
        manager.registerEvents(new TriggerListener(this, service, placed), this);
        manager.registerEvents(menus, this);
        DistanceTracker distance = new DistanceTracker(service);
        manager.registerEvents(distance, this);
        distance.start(this);

        var command = getCommand("questly");
        if (command != null) {
            QuestlyCommand handler = new QuestlyCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        if (manager.isPluginEnabled("PlaceholderAPI")) {
            new QuestlyExpansion(this).register();
        }

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            service.tick();
            menus.refresh();
        }, 20L, 20L);
        saveTask = scheduleSaveTask();

        for (Player player : Bukkit.getOnlinePlayers()) {
            service.deliverPending(player);
        }
        Metrics.start(this);
        Banner.print(this, "Thanks for giving every server something worth racing for.");
    }

    @Override
    public void onDisable() {
        if (service != null) service.saveAll();
        if (placed != null) placed.flush();
        if (storage != null) storage.close();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.deliverPending(event.getPlayer());
    }

    /** Names the game knows, for checking the quests. */
    private static final QuestParser.Names NAMES = new QuestParser.Names() {
        @Override
        public boolean entity(String name) {
            // NamespacedKey.minecraft(...) throws for anything outside [a-z0-9/._-] (a space, an
            // explicit "minecraft:" prefix, mixed case...) instead of just saying "not an entity" -
            // a single typo'd extra: in quests.yml would otherwise crash onEnable or a reload.
            NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
            return key != null && Registry.ENTITY_TYPE.get(key) != null;
        }

        @Override
        public boolean block(String name) {
            Material material = Material.matchMaterial(name);
            return material != null && material.isBlock();
        }

        @Override
        public boolean item(String name) {
            Material material = Material.matchMaterial(name);
            return material != null && material.isItem();
        }
    };

    /** @throws InvalidConfigurationException or IOException if quests.yml doesn't parse. */
    private QuestLibrary loadQuests() throws InvalidConfigurationException, IOException {
        File file = new File(getDataFolder(), "quests.yml");
        if (!file.exists()) saveResource("quests.yml", false);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        List<dev.questly.quest.Quest> loaded = QuestParser.parse(yaml, getLogger(), NAMES);
        return new QuestLibrary(loaded);
    }

    private BukkitTask scheduleSaveTask() {
        long save = settings.saveSeconds() * 20L;
        return Bukkit.getScheduler().runTaskTimer(this, () -> {
            service.saveDirty();
            placed.flush();
        }, save, save);
    }

    /**
     * Reads every file again. Returns how many quests there are. If quests.yml is broken, the quests
     * already loaded (and every board slot's progress) are kept instead of being wiped - a plain
     * {@link YamlConfiguration#loadConfiguration} would otherwise turn a YAML typo into an empty
     * config with no error, and an empty library clears every slot.
     */
    public int reloadAll() {
        reloadConfig();
        settings = new Settings(getConfig());
        messages.load();
        try {
            quests = loadQuests();
        } catch (InvalidConfigurationException | IOException e) {
            getLogger().log(Level.SEVERE, "quests.yml is broken, keeping the quests already loaded", e);
            return quests.size();
        }
        service.board().setLibrary(quests);
        service.board().configure(settings.slots(), settings.cooldownSeconds(), settings.uniqueQuests());
        storage.deleteSlotsFrom(settings.slots());
        service.board().fill();
        menus.loadShop();
        service.saveAll();
        if (saveTask != null) saveTask.cancel();
        saveTask = scheduleSaveTask();
        return quests.size();
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public QuestLibrary quests() {
        return quests;
    }

    public BoardService service() {
        return service;
    }

    public Menus menus() {
        return menus;
    }
}
