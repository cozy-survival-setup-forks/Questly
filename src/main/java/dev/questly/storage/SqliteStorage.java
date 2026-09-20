package dev.questly.storage;

import dev.questly.board.Board;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Saves points, the board and the rewards waiting for players who were offline. Everything runs on one thread, in
 * the order it was asked, so the server never waits for the disk.
 */
public final class SqliteStorage implements AutoCloseable {

    public record PlayerPoints(UUID id, String name, int points) {
    }

    public record Loaded(List<PlayerPoints> points, List<Board.Saved> slots) {
    }

    private final File file;
    private final Logger log;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Questly-Storage");
        thread.setDaemon(true);
        return thread;
    });
    private Connection connection;

    public SqliteStorage(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    /** Opens the file, creates the tables and reads everything. Waits for it. */
    public Loaded open() throws Exception {
        return executor.submit(() -> {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new SQLException("Could not create " + parent);
            }
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getPath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("CREATE TABLE IF NOT EXISTS questly_points (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, points INTEGER NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS questly_slots (slot INTEGER PRIMARY KEY, quest TEXT NOT NULL, active INTEGER NOT NULL, started INTEGER NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS questly_scores (slot INTEGER NOT NULL, uuid TEXT NOT NULL, name TEXT NOT NULL, score REAL NOT NULL, PRIMARY KEY (slot, uuid))");
                statement.execute("CREATE TABLE IF NOT EXISTS questly_pending (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, command TEXT NOT NULL)");
            }
            return read();
        }).get();
    }

    private Loaded read() throws SQLException {
        List<PlayerPoints> points = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT uuid, name, points FROM questly_points")) {
            while (rows.next()) {
                points.add(new PlayerPoints(UUID.fromString(rows.getString(1)), rows.getString(2), rows.getInt(3)));
            }
        }

        Map<Integer, List<Board.Score>> scores = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT slot, uuid, name, score FROM questly_scores")) {
            while (rows.next()) {
                scores.computeIfAbsent(rows.getInt(1), key -> new ArrayList<>())
                        .add(new Board.Score(UUID.fromString(rows.getString(2)), rows.getString(3), rows.getDouble(4)));
            }
        }

        List<Board.Saved> slots = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT slot, quest, active, started FROM questly_slots ORDER BY slot")) {
            while (rows.next()) {
                int slot = rows.getInt(1);
                slots.add(new Board.Saved(slot, rows.getString(2), rows.getInt(3) != 0, rows.getLong(4),
                        scores.getOrDefault(slot, List.of())));
            }
        }
        return new Loaded(points, slots);
    }

    public void savePoints(UUID player, String name, int points) {
        run("saving points", () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO questly_points (uuid, name, points) VALUES (?, ?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, points = excluded.points")) {
                statement.setString(1, player.toString());
                statement.setString(2, name);
                statement.setInt(3, points);
                statement.executeUpdate();
            }
        });
    }

    /** Saves a slot and its scores in one step, so a crash never leaves half of them. */
    public void saveSlot(Board.Saved slot) {
        run("saving the board", () -> {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT OR REPLACE INTO questly_slots (slot, quest, active, started) VALUES (?, ?, ?, ?)")) {
                    statement.setInt(1, slot.slot());
                    statement.setString(2, slot.questId());
                    statement.setInt(3, slot.active() ? 1 : 0);
                    statement.setLong(4, slot.startedAt());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM questly_scores WHERE slot = ?")) {
                    statement.setInt(1, slot.slot());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO questly_scores (slot, uuid, name, score) VALUES (?, ?, ?, ?)")) {
                    for (Board.Score score : slot.scores()) {
                        statement.setInt(1, slot.slot());
                        statement.setString(2, score.player().toString());
                        statement.setString(3, score.name());
                        statement.setDouble(4, score.score());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    /** A reward command for a player who is not online. It runs when they join. */
    public void addPending(UUID player, String command) {
        run("saving a reward", () -> {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO questly_pending (uuid, command) VALUES (?, ?)")) {
                statement.setString(1, player.toString());
                statement.setString(2, command);
                statement.executeUpdate();
            }
        });
    }

    /** Takes the rewards waiting for a player out of the file. */
    public CompletableFuture<List<String>> takePending(UUID player) {
        CompletableFuture<List<String>> result = new CompletableFuture<>();
        executor.execute(() -> {
            List<String> commands = new ArrayList<>();
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement select = connection.prepareStatement("SELECT command FROM questly_pending WHERE uuid = ? ORDER BY id");
                     PreparedStatement delete = connection.prepareStatement("DELETE FROM questly_pending WHERE uuid = ?")) {
                    select.setString(1, player.toString());
                    try (ResultSet rows = select.executeQuery()) {
                        while (rows.next()) commands.add(rows.getString(1));
                    }
                    delete.setString(1, player.toString());
                    delete.executeUpdate();
                    connection.commit();
                } catch (SQLException ex) {
                    connection.rollback();
                    throw ex;
                } finally {
                    connection.setAutoCommit(true);
                }
                result.complete(commands);
            } catch (SQLException ex) {
                log.log(Level.WARNING, "Could not read the rewards waiting for " + player, ex);
                result.complete(List.of());
            }
        });
        return result;
    }

    private interface Job {
        void run() throws SQLException;
    }

    private void run(String what, Job job) {
        executor.execute(() -> {
            try {
                job.run();
            } catch (SQLException ex) {
                log.log(Level.WARNING, "Problem while " + what, ex);
            }
        });
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                log.warning("Saving is taking long, some of the last changes may not be written.");
            }
            if (connection != null) connection.close();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (SQLException ex) {
            log.log(Level.WARNING, "Could not close the database", ex);
        }
    }
}
