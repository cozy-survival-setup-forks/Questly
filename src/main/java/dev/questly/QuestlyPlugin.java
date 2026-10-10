package dev.questly;

import dev.questly.board.Board;
import dev.questly.board.Points;
import dev.questly.command.QuestlyCommand;
import dev.questly.gui.Menus;
import dev.questly.hook.QuestlyExpansion;
import dev.questly.listener.DistanceTracker;
import dev.questly.listener.PlacedBlocks;
import dev.questly.listener.TriggerListener;
import dev.questly.quest.Category;
import dev.questly.safe.ConfigMigrator;
import dev.questly.safe.Doctor;
import dev.questly.safe.FileBackups;
import dev.questly.safe.Guard;
import dev.questly.safe.Health;
import dev.questly.safe.Prep;
import dev.questly.safe.SafeIo;
import dev.questly.safe.ServerId;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.logging.Level;

/**
 * Questly: a board of quests that players race to finish. The quests are in quests.yml, and everything is
 * kept in a small file database.
 */
public final class QuestlyPlugin extends JavaPlugin implements Listener {

    private static final int CONFIG_VERSION = 1;
    private static final int LANG_VERSION = 1;
    /** Lists the owner writes: only kept readable and backed up, nothing is added to them. */
    private static final List<String> CONTENT_FILES = List.of("quests.yml", "shop.yml");

    private final List<Prep.Spec> files = List.of(
            new Prep.Spec("config.yml", "config-version", CONFIG_VERSION, Prep.configMigrator(CONFIG_VERSION), rules -> {
                rules.range("backup.interval-hours", 1, 168);
                rules.range("backup.keep", 1, 90);
            }),
            new Prep.Spec("lang.yml", "lang-version", LANG_VERSION, new ConfigMigrator("lang-version", LANG_VERSION), null));

    private BukkitTask backupTask;
    private boolean started;
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
        Health.storage("SQLite data.db for points, the board and queued rewards; YAML for config.yml, lang.yml, quests.yml and shop.yml");
        Prep.startup(this, files);
        for (String name : CONTENT_FILES) {
            // a damaged one is put back from its .bak; without one it is left as it is, as before
            Path file = getDataFolder().toPath().resolve(name);
            if (Files.exists(file) && !SafeIo.parses(file) && SafeIo.parses(SafeIo.backupOf(file))) {
                SafeIo.loadYaml(file, SafeIo.Policy.SETTINGS, getLogger());
            } else {
                SafeIo.refreshBackup(file);
            }
        }
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
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
        board.configure(List.of(Category.values()), settings.slots(), settings.cooldownSeconds(), settings.uniqueQuests());
        Points points = new Points();

        storage = new SqliteStorage(new File(getDataFolder(), "data.db"), getLogger());
        try {
            SqliteStorage.Loaded loaded = storage.open(backupKeep());
            loaded.points().forEach(saved -> points.load(saved.id(), saved.name(), saved.points()));
            board.restore(loaded.slots());
            // If slots: was ever shrunk without a matching cleanup (an older version of this plugin
            // didn't do one), drop whatever's left past the current count now, before it can come
            // back to life on some future restart where slots: is raised again.
            storage.deleteSlotsFrom(board.slots().size());
        } catch (Exception ex) {
            // points and queued rewards are never replaced by an empty set: the plugin stays off until it is sorted out
            getLogger().log(Level.SEVERE, "Questly cannot use data.db and is switching itself off so nothing is reset or paid twice: " + ex.getMessage());
            Health.failure("data.db could not be opened: " + ex.getMessage());
            storage = null;
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
            var shop = getCommand("questshop");
            if (shop != null) {
                shop.setExecutor((sender, cmd, label, args) -> {
                    handler.openShop(sender);
                    return true;
                });
            }
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
        boolean beacon = getConfig().getBoolean("metrics.enabled", true);
        Metrics.start(this, ServerId.resolve(getDataFolder().toPath(), storage.serverIdSlot(), beacon, getLogger()));
        scheduleBackups();
        started = true;
        Banner.print(this, "Thanks for giving every server something worth racing for.");
    }

    @Override
    public void onDisable() {
        if (backupTask != null) backupTask.cancel();
        if (service != null) service.saveAll();
        if (placed != null) placed.flush();
        // waits (up to 15 seconds) for the writes that are queued, then closes the database
        if (storage != null) storage.close();
    }

    private int backupKeep() {
        return Math.max(1, Math.min(90, getConfig().getInt("backup.keep", 7)));
    }

    /** (Re)starts the timer of the database copies from backup.interval-hours and backup.keep. */
    private void scheduleBackups() {
        int hours = Math.max(1, Math.min(168, getConfig().getInt("backup.interval-hours", 6)));
        storage.configureBackups(backupKeep());
        if (backupTask != null) backupTask.cancel();
        backupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, storage::backup, 20L * 60, hours * 3600L * 20L);
    }

    /** The text of /questly doctor. */
    public List<String> doctor() {
        List<String> extra = new ArrayList<>(Prep.versionLines(this, files));
        extra.add("Database: data.db, schema " + storage.schemaVersion() + " (this plugin writes " + SqliteStorage.SCHEMA + ")");
        String newest = storage.newestBackup();
        extra.add("Newest database copy on disk: " + (newest == null ? "none yet" : newest));
        extra.add("Pending writes: " + storage.pendingWrites() + ", failed writes since start: " + storage.failedWrites());
        try {
            List<String> unknown = storage.journal() == null ? List.of() : storage.journal().unknown();
            extra.add("Payouts that may or may not have been made (not repeated): " + unknown.size());
            unknown.forEach(line -> extra.add("  " + line + "   (after checking: /questly doctor resolve <id>)"));
        } catch (SQLException e) {
            extra.add("Payout record could not be read: " + e.getMessage());
        }
        return Doctor.report(getName(), getPluginMeta().getVersion(), extra);
    }

    public boolean resolvePayout(String id) {
        try {
            return storage.journal() != null && storage.journal().resolve(id);
        } catch (SQLException e) {
            getLogger().severe("Could not update the payout record: " + e.getMessage());
            return false;
        }
    }

    /** /questly backup now: a checked copy of the database and of the settings and quest files. */
    public boolean backupNow() {
        service.saveAll();
        boolean written = storage.flush();
        boolean database = storage.backup();
        List<String> names = new ArrayList<>(Prep.fileNames(files));
        names.addAll(CONTENT_FILES);
        boolean settingsFiles = FileBackups.snapshot(getDataFolder().toPath(), names, 5, getLogger());
        return written && database && settingsFiles;
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
        if (started) {
            List<Guard.Problem> problems = Prep.validate(this, files);
            if (!problems.isEmpty()) {
                Prep.logRejected(this, problems);
                return -1;
            }
        }
        reloadConfig();
        scheduleBackups();
        settings = new Settings(getConfig(), getLogger());
        messages.load();
        try {
            quests = loadQuests();
        } catch (InvalidConfigurationException | IOException e) {
            getLogger().log(Level.SEVERE, "quests.yml is broken, keeping the quests already loaded", e);
            return quests.size();
        }
        service.board().setLibrary(quests);
        service.board().configure(List.of(Category.values()), settings.slots(), settings.cooldownSeconds(), settings.uniqueQuests());
        storage.deleteSlotsFrom(service.board().slots().size());
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
