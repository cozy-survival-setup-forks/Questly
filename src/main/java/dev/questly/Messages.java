package dev.questly;

import dev.questly.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** The texts in lang.yml. A text missing from an older file falls back to the default one. */
public final class Messages {

    private final QuestlyPlugin plugin;
    private FileConfiguration file = new YamlConfiguration();

    Messages(QuestlyPlugin plugin) {
        this.plugin = plugin;
    }

    void load() {
        File target = new File(plugin.getDataFolder(), "lang.yml");
        if (!target.exists()) plugin.saveResource("lang.yml", false);
        file = YamlConfiguration.loadConfiguration(target);

        try (InputStream defaults = plugin.getResource("lang.yml")) {
            if (defaults != null) {
                try (Reader reader = new InputStreamReader(defaults, StandardCharsets.UTF_8)) {
                    file.setDefaults(YamlConfiguration.loadConfiguration(reader));
                    // Every lookup below passes its own explicit default ("") to getString/
                    // getStringList, which skips consulting getDefaults() entirely - without this, a
                    // key missing from an admin's older lang.yml just goes silently empty instead of
                    // using the bundled text.
                    file.options().copyDefaults(true);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read the bundled lang.yml: " + e.getMessage());
        }
    }

    public String raw(String key) {
        return file.getString(key, "");
    }

    /** Sends a message with the prefix. An empty message is skipped, so any of them can be turned off. */
    public void send(CommandSender to, String key, Map<String, String> values) {
        if (file.isList(key)) {
            for (String line : file.getStringList(key)) {
                to.sendMessage(Text.component(Text.fill(line, values)));
            }
            return;
        }
        String text = file.getString(key, "");
        if (text.isEmpty()) return;
        to.sendMessage(Text.component(Text.fill(file.getString("prefix", "") + text, values)));
    }

    public void send(CommandSender to, String key) {
        send(to, key, Map.of());
    }

    public void broadcast(String key, Map<String, String> values) {
        String text = file.getString(key, "");
        if (text.isEmpty()) return;
        var message = Text.component(Text.fill(file.getString("prefix", "") + text, values));
        Bukkit.getServer().sendMessage(message);
    }
}
