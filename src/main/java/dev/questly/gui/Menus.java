package dev.questly.gui;

import dev.questly.BoardService;
import dev.questly.QuestlyPlugin;
import dev.questly.board.Board;
import dev.questly.gui.ShopParser.Product;
import dev.questly.quest.Quest;
import dev.questly.util.Duration;
import dev.questly.util.Text;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/** The quest board and the points shop. Nothing can be taken out of them, a click only asks for a purchase. */
public final class Menus implements Listener {

    private static final int LEADERBOARD_PLACES = 5;

    private static final class BoardHolder implements InventoryHolder {
        Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private static final class ShopHolder implements InventoryHolder {
        Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private final QuestlyPlugin plugin;
    private final BoardService service;
    private FileConfiguration shop = new YamlConfiguration();
    private final Map<Integer, Product> products = new LinkedHashMap<>();

    public Menus(QuestlyPlugin plugin, BoardService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void loadShop() {
        File file = new File(plugin.getDataFolder(), "shop.yml");
        if (!file.exists()) plugin.saveResource("shop.yml", false);
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.SEVERE, "shop.yml is broken, keeping the shop as it was", e);
            return;
        }
        shop = loaded;

        products.clear();
        products.putAll(ShopParser.parse(shop.getConfigurationSection("items"), shopSize(), plugin.getLogger(), name -> {
            Material type = Material.matchMaterial(name);
            return type != null && type.isItem();
        }));
    }

    /** {@code size} (a DeluxeMenus-style inventory row count) if set, otherwise the older {@code rows}. */
    private int shopSize() {
        if (shop.contains("size")) {
            int size = shop.getInt("size", 27);
            // A chest inventory must be a multiple of 9 - anything else throws when the shop opens.
            int rounded = Math.max(1, Math.min(6, (size + 8) / 9)) * 9;
            if (rounded != size) plugin.getLogger().warning("shop.yml: size " + size + " is not a multiple of 9, using " + rounded);
            return rounded;
        }
        return Math.max(1, Math.min(6, shop.getInt("rows", 3))) * 9;
    }

    // ---- items ----

    private static @Nullable ItemStack item(String material, String name, List<String> lore, Map<String, String> values) {
        Material type = Material.matchMaterial(material);
        if (type == null || !type.isItem()) return null;

        ItemStack stack = new ItemStack(type);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.displayName(Text.item(Text.fill(name, values)));
        meta.lore(lore.stream().map(line -> Text.item(Text.fill(line, values))).toList());
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack filler(String material) {
        ItemStack stack = item(material, " ", List.of(), Map.of());
        return stack == null ? new ItemStack(Material.BLACK_STAINED_GLASS_PANE) : stack;
    }

    // ---- the board ----

    private Map<String, String> values(Board.Slot slot, Player viewer) {
        Quest quest = slot.quest();
        Map<String, String> values = new HashMap<>();
        if (quest == null) return values;

        values.put("%title%", quest.titleText());
        values.put("%required%", String.valueOf(quest.required()));
        values.put("%points%", String.valueOf(quest.winPoints()));
        values.put("%score%", Text.number(Math.floor(slot.scoreOf(viewer.getUniqueId()))));

        List<Board.Entry> ranking = slot.ranking();
        for (int place = 1; place <= LEADERBOARD_PLACES; place++) {
            Board.Entry entry = place <= ranking.size() ? ranking.get(place - 1) : null;
            values.put("%top_" + place + "_name%", entry == null ? "---" : entry.name());
            values.put("%top_" + place + "_score%", entry == null ? "0" : Text.number(Math.floor(entry.score())));
        }
        values.put("%winner%", ranking.isEmpty() ? "---" : ranking.get(0).name());
        values.put("%time%", Duration.clock(service.board().restLeft(slot)));
        return values;
    }

    private ItemStack questItem(Board.Slot slot, Player viewer) {
        Quest quest = slot.quest();
        if (quest == null) return filler(plugin.settings().filler());

        Map<String, String> values = values(slot, viewer);
        ItemStack stack;
        if (slot.active()) {
            stack = item(quest.display().material(), quest.display().name(), quest.display().lore(), values);
        } else {
            Quest.Display rest = plugin.settings().cooldownItem();
            stack = item(rest.material(), rest.name(), rest.lore(), values);
        }
        return stack == null ? filler(plugin.settings().filler()) : stack;
    }

    /** Rows of the board: {@code gui.rows} if set, otherwise enough for every quest with a filler row above and below. */
    private int boardRows(int quests) {
        int needed = Math.max(1, (quests + 8) / 9);
        int rows = plugin.settings().guiRows();
        if (rows <= 0) rows = Math.min(6, needed + 2);
        return Math.max(needed, Math.min(6, rows));
    }

    /**
     * The inventory slot of every quest, in order. {@code gui.quest-slots} if it has enough valid slots, otherwise the
     * quests fill the rows in the middle of the board.
     */
    private List<Integer> questSlots(int quests, int size) {
        List<Integer> configured = new ArrayList<>();
        for (int slot : plugin.settings().guiQuestSlots()) {
            if (slot >= 0 && slot < size && !configured.contains(slot)) configured.add(slot);
        }
        if (configured.size() >= quests) return configured.subList(0, quests);

        int rows = size / 9;
        int used = (quests + 8) / 9;
        int first = ((rows - used) / 2) * 9;
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < quests; i++) slots.add(first + i);
        return slots;
    }

    public void openBoard(Player player) {
        int quests = service.board().slots().size();
        BoardHolder holder = new BoardHolder();
        holder.inventory = Bukkit.createInventory(holder, boardRows(quests) * 9, Text.component(plugin.settings().guiTitle()));
        fillBoard(holder.inventory, player);
        player.openInventory(holder.inventory);
    }

    private void fillBoard(Inventory inventory, Player viewer) {
        List<Board.Slot> quests = service.board().slots();
        List<Integer> where = questSlots(quests.size(), inventory.getSize());
        ItemStack fill = filler(plugin.settings().filler());
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, fill);
        for (int i = 0; i < quests.size() && i < where.size(); i++) {
            int slot = where.get(i);
            // A reload that raises the slot count can hand back an index past the size of a board
            // that's already open - refresh() runs every second, so this would otherwise throw
            // repeatedly until the viewer closes it.
            if (slot < inventory.getSize()) inventory.setItem(slot, questItem(quests.get(i), viewer));
        }
    }

    /** Keeps the timers and scores of open boards current. Called once a second. */
    public void refresh() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder(false) instanceof BoardHolder) {
                fillBoard(top, player);
            } else if (top.getHolder(false) instanceof ShopHolder) {
                fillShop(top, player);
            }
        }
    }

    // ---- the shop ----

    public void openShop(Player player) {
        ShopHolder holder = new ShopHolder();
        String title = shop.contains("menu_title") ? shop.getString("menu_title", "Quest Shop") : shop.getString("title", "Quest Shop");
        holder.inventory = Bukkit.createInventory(holder, shopSize(), Text.component(title));
        fillShop(holder.inventory, player);
        player.openInventory(holder.inventory);
    }

    private void fillShop(Inventory inventory, Player viewer) {
        String fill = shop.getString("filler", "BLACK_STAINED_GLASS_PANE").toUpperCase(Locale.ROOT);
        Map<String, String> values = Map.of("%points%", String.valueOf(service.points().get(viewer.getUniqueId())));
        for (int i = 0; i < inventory.getSize(); i++) {
            Product product = products.get(i);
            ItemStack stack = product == null ? null : item(product.material(), product.name(), product.lore(), withPrice(values, product));
            inventory.setItem(i, stack != null ? stack : filler(fill));
        }
    }

    private static Map<String, String> withPrice(Map<String, String> values, Product product) {
        Map<String, String> all = new HashMap<>(values);
        all.put("%price%", String.valueOf(product.price()));
        return all;
    }

    private void buy(Player player, Product product) {
        if (!product.free() && !service.points().spend(player.getUniqueId(), player.getName(), product.price())) {
            plugin.messages().send(player, "not-enough-points", Map.of(
                    "%price%", String.valueOf(product.price()),
                    "%points%", String.valueOf(service.points().get(player.getUniqueId()))));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        // Deferred a tick: Bukkit doesn't allow closing (or opening another) inventory from inside an
        // InventoryClickEvent, and a console command such as "dm open <menu> %player%" does exactly that.
        List<String> commands = product.commands();
        if (!commands.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> commands.forEach(command -> runCommand(player, command)));
        }
        for (ConfigurationSection line : product.give()) {
            ItemStack stack = item(line.getString("material", "STONE").toUpperCase(Locale.ROOT),
                    line.getString("name", ""), line.getStringList("lore"), Map.of());
            if (stack == null) continue;
            if (line.getString("name", "").isEmpty()) {
                ItemMeta meta = stack.getItemMeta();
                meta.displayName(null);
                stack.setItemMeta(meta);
            }
            stack.setAmount(Math.max(1, Math.min(stack.getMaxStackSize(), line.getInt("amount", 1))));
            player.getInventory().addItem(stack).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
        // A free button or decoration (price 0) says nothing unless it handed over an item.
        if (product.free() && product.give().isEmpty()) return;

        plugin.messages().send(player, "shop-bought", Map.of("%item%",
                PlainTextComponentSerializer.plainText().serialize(Text.component(product.name()))));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.1f);
        // The commands run next tick, so the player is still looking at this shop: show the new points.
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder(false) instanceof ShopHolder) fillShop(top, player);
    }

    /** A shop reward line, run a tick after the click. Same tags as our menus: {@code [console]} (the default),
     * {@code [player]}, {@code [message]} and {@code [close]}. */
    private void runCommand(Player player, String line) {
        Text.Tagged tagged = Text.tag(line);
        String filled = tagged.rest().replace("%player%", player.getName());
        // A console reward is still owed to a player who left in that tick, the rest needs them online.
        if (List.of("player", "message", "close").contains(tagged.tag()) && !player.isOnline()) return;
        switch (tagged.tag()) {
            case "player" -> player.performCommand(filled);
            case "message" -> player.sendMessage(Text.component(filled));
            case "close" -> player.closeInventory();
            default -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), filled);
        }
    }

    // ---- clicks ----

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
        if (!(holder instanceof BoardHolder) && !(holder instanceof ShopHolder)) return;

        event.setCancelled(true);
        // Only a genuine single purchase click - a double-click, a shift-click, a number-key swap
        // or a drop-key all send their own InventoryClickEvent for the same slot, and would otherwise
        // buy the product again for every one of them.
        ClickType click = event.getClick();
        boolean singlePurchaseClick = click == ClickType.LEFT || click == ClickType.RIGHT;
        if (holder instanceof ShopHolder && singlePurchaseClick && event.getWhoClicked() instanceof Player player
                && event.getClickedInventory() == event.getView().getTopInventory()) {
            Product product = products.get(event.getSlot());
            if (product != null) buy(player, product);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
        if (holder instanceof BoardHolder || holder instanceof ShopHolder) event.setCancelled(true);
    }
}
