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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Random;

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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig());
        messages = new Messages(this);
        messages.load();
        quests = loadQuests();
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
        } catch (Exception ex) {
            getLogger().severe("Could not open data.db, disabling Questly: " + ex);
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
        long save = settings.saveSeconds() * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            service.saveDirty();
            placed.flush();
        }, save, save);

        for (Player player : Bukkit.getOnlinePlayers()) {
            service.deliverPending(player);
        }
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
            return Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT))) != null;
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

    private QuestLibrary loadQuests() {
        File file = new File(getDataFolder(), "quests.yml");
        if (!file.exists()) saveResource("quests.yml", false);
        List<dev.questly.quest.Quest> loaded = QuestParser.parse(YamlConfiguration.loadConfiguration(file), getLogger(), NAMES);
        return new QuestLibrary(loaded);
    }

    /** Reads every file again. Returns how many quests there are. */
    public int reloadAll() {
        reloadConfig();
        settings = new Settings(getConfig());
        messages.load();
        quests = loadQuests();
        service.board().setLibrary(quests);
        service.board().configure(settings.slots(), settings.cooldownSeconds(), settings.uniqueQuests());
        service.board().fill();
        menus.loadShop();
        service.saveAll();
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
