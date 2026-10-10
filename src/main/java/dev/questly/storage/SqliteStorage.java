package dev.questly.storage;

import dev.questly.board.Board;
import dev.questly.safe.Db;
import dev.questly.safe.DbBackups;
import dev.questly.safe.Health;
import dev.questly.safe.Journal;
import dev.questly.safe.ServerId;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Saves points, the board and the rewards waiting for players who were offline, in {@code data.db} (SQLite). Writes run
 * on one thread, in the order they were asked, so the server never waits for the disk; the few that must be on disk
 * before something is paid wait for it ({@link #saveSlotNow}, {@link #flush}). The file is checked when it opens, a damaged
 * one is replaced by the newest backup that verifies, and a file made by a newer version is left alone.
 */
public final class SqliteStorage implements AutoCloseable {

    public static final int SCHEMA = 1;
    private static final String BACKUP_PREFIX = "questly";

    public record PlayerPoints(UUID id, String name, int points) {
    }

    public record Loaded(List<PlayerPoints> points, List<Board.Saved> slots) {
    }

    /** Rewards taken out of the queue of a player, with the id of the payout record that covers them. */
    public record Claimed(List<String> commands, String record) {
    }

    private final File file;
    private final Logger log;
    private final Path backupDir;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Questly-Storage");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong failedWrites = new AtomicLong();
    private Db db;
    private Journal journal;
    private DbBackups backups;

    public SqliteStorage(File file, Logger log) {
        this.file = file;
        this.log = log;
        File parent = file.getAbsoluteFile().getParentFile();
        this.backupDir = parent.toPath().resolve("backups");
    }

    /** Opens the file, creates the tables and reads everything. Waits for it. */
    public Loaded open(int backupKeep) throws Exception {
        return executor.submit(() -> {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new SQLException("Could not create " + parent);
            }
            Class.forName("org.sqlite.JDBC");
            db = Db.openRecovering(file.toPath(), SCHEMA, backupDir, BACKUP_PREFIX, true, log);
            try {
                db.tx(c -> {
                    try (Statement statement = c.createStatement()) {
                        statement.execute("CREATE TABLE IF NOT EXISTS questly_points (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, points INTEGER NOT NULL)");
                        statement.execute("CREATE TABLE IF NOT EXISTS questly_slots (slot INTEGER PRIMARY KEY, quest TEXT NOT NULL, active INTEGER NOT NULL, started INTEGER NOT NULL)");
                        statement.execute("CREATE TABLE IF NOT EXISTS questly_scores (slot INTEGER NOT NULL, uuid TEXT NOT NULL, name TEXT NOT NULL, score REAL NOT NULL, PRIMARY KEY (slot, uuid))");
                        statement.execute("CREATE TABLE IF NOT EXISTS questly_pending (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, command TEXT NOT NULL)");
                        statement.execute("CREATE TABLE IF NOT EXISTS questly_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                    }
                });
                if (db.userVersion() < SCHEMA) db.setUserVersion(SCHEMA);
                journal = new Journal(db);
                journal.recover(log);
                Loaded loaded = db.read(this::read);
                configureBackups(backupKeep);
                Health.file("data.db", "in use");
                return loaded;
            } catch (SQLException | RuntimeException e) {
                db.close();
                db = null;
                throw e;
            }
        }).get();
    }

    public void configureBackups(int keep) {
        if (db != null) backups = new DbBackups(db, backupDir, BACKUP_PREFIX, keep, log);
    }

    public boolean backup() {
        return backups == null || backups.run();
    }

    public Journal journal() {
        return journal;
    }

    public int pendingWrites() {
        return queued.get();
    }

    public long failedWrites() {
        return failedWrites.get();
    }

    public int schemaVersion() {
        try {
            return db == null ? SCHEMA : db.userVersion();
        } catch (SQLException e) {
            return -1;
        }
    }

    public String newestBackup() {
        List<Path> found = DbBackups.list(backupDir, BACKUP_PREFIX);
        return found.isEmpty() ? null : found.get(0).getFileName().toString();
    }

    public ServerId.Slot serverIdSlot() {
        return new ServerId.Slot() {
            @Override
            public String read() throws SQLException {
                return db.read(c -> {
                    try (PreparedStatement ps = c.prepareStatement("SELECT value FROM questly_meta WHERE key='server-id'");
                         ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                });
            }

            @Override
            public void write(String id) throws SQLException {
                db.tx(c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO questly_meta (key, value) VALUES ('server-id', ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                        ps.setString(1, id);
                        ps.executeUpdate();
                    }
                });
            }
        };
    }

    private Loaded read(Connection connection) throws SQLException {
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
        run("saving points", () -> db.tx(c -> {
            try (PreparedStatement statement = c.prepareStatement(
                    "INSERT INTO questly_points (uuid, name, points) VALUES (?, ?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, points = excluded.points")) {
                statement.setString(1, player.toString());
                statement.setString(2, name);
                statement.setInt(3, points);
                statement.executeUpdate();
            }
        }));
    }

    /** Saves a slot and its scores in one step, so a crash never leaves half of them. */
    public void saveSlot(Board.Saved slot) {
        run("saving the board", () -> writeSlot(slot));
    }

    /** Like {@link #saveSlot}, and waits until it is on disk. @return false when it could not be written */
    public boolean saveSlotNow(Board.Saved slot) {
        queued.incrementAndGet();
        try {
            executor.submit(() -> {
                try {
                    writeSlot(slot);
                    return true;
                } catch (SQLException ex) {
                    failedWrites.incrementAndGet();
                    Health.failure("the board could not be saved: " + ex.getMessage());
                    log.log(Level.WARNING, "Problem while saving the board", ex);
                    return false;
                } finally {
                    queued.decrementAndGet();
                }
            }).get(10, TimeUnit.SECONDS);
            return true;
        } catch (Exception ex) {
            log.log(Level.SEVERE, "The board could not be saved in time", ex);
            return false;
        }
    }

    private void writeSlot(Board.Saved slot) throws SQLException {
        db.tx(c -> {
            try (PreparedStatement statement = c.prepareStatement(
                    "INSERT OR REPLACE INTO questly_slots (slot, quest, active, started) VALUES (?, ?, ?, ?)")) {
                statement.setInt(1, slot.slot());
                statement.setString(2, slot.questId());
                statement.setInt(3, slot.active() ? 1 : 0);
                statement.setLong(4, slot.startedAt());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = c.prepareStatement("DELETE FROM questly_scores WHERE slot = ?")) {
                statement.setInt(1, slot.slot());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = c.prepareStatement(
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
        });
    }

    /** Waits until everything asked so far is written. @return false when that did not happen in time */
    public boolean flush() {
        long before = failedWrites.get();
        try {
            executor.submit(() -> { }).get(10, TimeUnit.SECONDS);
            return failedWrites.get() == before;
        } catch (Exception ex) {
            log.log(Level.SEVERE, "The saves did not finish in time", ex);
            return false;
        }
    }

    /**
     * Deletes any slot and score rows at or past {@code slotCount} - called whenever the board is
     * (re)configured, so shrinking {@code slots:} in config.yml and later raising it again doesn't
     * bring back a stale quest and leaderboard from before the shrink.
     */
    public void deleteSlotsFrom(int slotCount) {
        run("dropping unused board slots", () -> db.tx(c -> {
            try (PreparedStatement slots = c.prepareStatement("DELETE FROM questly_slots WHERE slot >= ?");
                 PreparedStatement scores = c.prepareStatement("DELETE FROM questly_scores WHERE slot >= ?")) {
                slots.setInt(1, slotCount);
                slots.executeUpdate();
                scores.setInt(1, slotCount);
                scores.executeUpdate();
            }
        }));
    }

    /** A reward command for a player who is not online. It runs when they join. */
    public void addPending(UUID player, String command) {
        run("saving a reward", () -> db.tx(c -> {
            try (PreparedStatement statement = c.prepareStatement("INSERT INTO questly_pending (uuid, command) VALUES (?, ?)")) {
                statement.setString(1, player.toString());
                statement.setString(2, command);
                statement.executeUpdate();
            }
        }));
    }

    /**
     * Takes the rewards waiting for a player out of the file. They are removed and a payout record is written in one
     * step, so a stop before they are paid is listed for a person to check and never leaves the rewards half there.
     */
    public CompletableFuture<Claimed> takePending(UUID player) {
        CompletableFuture<Claimed> result = new CompletableFuture<>();
        queued.incrementAndGet();
        executor.execute(() -> {
            try {
                List<String> commands = new ArrayList<>();
                String[] record = new String[1];
                db.tx(c -> {
                    try (PreparedStatement select = c.prepareStatement("SELECT command FROM questly_pending WHERE uuid = ? ORDER BY id");
                         PreparedStatement delete = c.prepareStatement("DELETE FROM questly_pending WHERE uuid = ?")) {
                        select.setString(1, player.toString());
                        try (ResultSet rows = select.executeQuery()) {
                            while (rows.next()) commands.add(rows.getString(1));
                        }
                        if (commands.isEmpty()) return;
                        delete.setString(1, player.toString());
                        delete.executeUpdate();
                    }
                    record[0] = UUID.randomUUID().toString();
                    try (PreparedStatement insert = c.prepareStatement(
                            "INSERT INTO op_journal (id, kind, state, detail, started_at) VALUES (?, 'queued-rewards', 'pending', ?, ?)")) {
                        insert.setString(1, record[0]);
                        insert.setString(2, "player=" + player + " commands=" + commands);
                        insert.setLong(3, System.currentTimeMillis());
                        insert.executeUpdate();
                    }
                });
                result.complete(new Claimed(commands, record[0]));
            } catch (SQLException | RuntimeException ex) {
                // Complete the future either way (never leave callers hanging) - but distinguish a
                // real read failure (the rewards are still in the file, so "nothing now" is safe) from an
                // unexpected bug, which should surface as a failed future.
                if (ex instanceof SQLException) {
                    failedWrites.incrementAndGet();
                    Health.failure("queued rewards could not be read: " + ex.getMessage());
                    log.log(Level.WARNING, "Could not read the rewards waiting for " + player, ex);
                    result.complete(new Claimed(List.of(), null));
                } else {
                    result.completeExceptionally(ex);
                }
            } finally {
                queued.decrementAndGet();
            }
        });
        return result;
    }

    // ---- the payout record

    /** Writes a pending record before something is paid. @return its id, or null when it could not be written */
    public String begin(String kind, String detail) {
        if (journal == null) return null;
        try {
            return journal.begin(kind, detail);
        } catch (SQLException ex) {
            Health.failure("the payout record could not be written: " + ex.getMessage());
            log.log(Level.SEVERE, "The payout record could not be written", ex);
            return null;
        }
    }

    public void finish(String record, boolean ok, String reason) {
        if (journal == null || record == null) return;
        try {
            if (ok) journal.succeeded(record);
            else journal.failed(record, reason);
        } catch (SQLException ex) {
            log.log(Level.WARNING, "The payout record could not be finished", ex);
        }
    }

    /** The payout is known to have gone wrong: it is listed for a person to check. */
    public void flag(String record, String reason) {
        if (journal == null || record == null) return;
        try {
            journal.flag(record, reason);
        } catch (SQLException ex) {
            log.log(Level.WARNING, "The payout record could not be updated", ex);
        }
    }

    private interface Job {
        void run() throws SQLException;
    }

    private void run(String what, Job job) {
        queued.incrementAndGet();
        executor.execute(() -> {
            try {
                job.run();
            } catch (SQLException ex) {
                failedWrites.incrementAndGet();
                Health.failure("problem while " + what + ": " + ex.getMessage());
                log.log(Level.WARNING, "Problem while " + what, ex);
            } finally {
                queued.decrementAndGet();
            }
        });
    }

    @Override
    public void close() {
        executor.shutdown();
        boolean stopped = false;
        try {
            stopped = executor.awaitTermination(15, TimeUnit.SECONDS);
            if (!stopped) {
                log.warning("Saving is taking long, forcing it to stop; some of the last changes may not be written.");
                // Closing the connection out from under a write that's still running throws a
                // confusing SQLException in that write instead of a clean shutdown - force the
                // executor to stop first and wait for it to actually finish before closing.
                executor.shutdownNow();
                stopped = executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (!stopped) {
            log.warning("The storage thread did not stop in time; leaving the database connection open rather than risk closing it mid-write.");
            return;
        }
        if (db != null) {
            db.close();
            db = null;
        }
    }
}
