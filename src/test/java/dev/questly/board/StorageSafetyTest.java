package dev.questly.board;

import dev.questly.safe.Db;
import dev.questly.storage.SqliteStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageSafetyTest {

    private static final Logger LOG = Logger.getAnonymousLogger();
    private static final UUID ALEX = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path dir;

    private SqliteStorage storage() {
        return new SqliteStorage(dir.resolve("data.db").toFile(), LOG);
    }

    @Test
    void aDamagedDatabaseIsNotReplacedByAnEmptyOne() throws Exception {
        try (SqliteStorage first = storage()) {
            first.open(3);
        }
        byte[] garbage = "this is not a database, not at all, not one bit".getBytes();
        Files.write(dir.resolve("data.db"), garbage);
        try (SqliteStorage damaged = storage()) {
            assertThrows(Exception.class, () -> damaged.open(3));
        }
        assertTrue(Arrays.equals(garbage, Files.readAllBytes(dir.resolve("data.db"))));
    }

    @Test
    void aDamagedDatabaseComesBackFromTheNewestGoodBackup() throws Exception {
        try (SqliteStorage first = storage()) {
            first.open(3);
            first.savePoints(ALEX, "Alex", 42);
            assertTrue(first.flush());
            assertTrue(first.backup());
        }
        Files.write(dir.resolve("data.db"), "garbage garbage garbage garbage".getBytes());
        Files.deleteIfExists(dir.resolve("data.db-wal"));
        try (SqliteStorage again = storage()) {
            SqliteStorage.Loaded loaded = again.open(3);
            assertEquals(1, loaded.points().size());
            assertEquals(42, loaded.points().get(0).points());
        }
    }

    @Test
    void aDatabaseFromANewerVersionIsNotTouched() throws Exception {
        try (SqliteStorage first = storage()) {
            first.open(3);
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("data.db").toAbsolutePath());
             Statement s = c.createStatement()) {
            s.execute("PRAGMA user_version=99");
        }
        byte[] before = Files.readAllBytes(dir.resolve("data.db"));
        try (SqliteStorage newer = storage()) {
            Exception e = assertThrows(Exception.class, () -> newer.open(3));
            assertTrue(e.getCause() instanceof Db.NewerSchemaException, String.valueOf(e.getCause()));
        }
        assertTrue(Arrays.equals(before, Files.readAllBytes(dir.resolve("data.db"))));
    }

    @Test
    void rewardsThatWereTakenButNotPaidAreFlaggedNotPaidAgain() throws Exception {
        try (SqliteStorage first = storage()) {
            first.open(3);
            first.addPending(ALEX, "give Alex diamond 1");
            SqliteStorage.Claimed claimed = first.takePending(ALEX).get();
            assertEquals(List.of("give Alex diamond 1"), claimed.commands());
            assertNotNull(claimed.record());
            // the server stopped here, before they were paid
        }
        try (SqliteStorage again = storage()) {
            again.open(3);
            assertTrue(again.takePending(ALEX).get().commands().isEmpty(), "they are not handed out a second time");
            assertEquals(1, again.journal().unknown().size(), "and the missed payout is listed for a person to check");
        }
    }

    @Test
    void rewardsThatWerePaidLeaveNothingToCheck() throws Exception {
        try (SqliteStorage first = storage()) {
            first.open(3);
            first.addPending(ALEX, "say hi");
            SqliteStorage.Claimed claimed = first.takePending(ALEX).get();
            first.finish(claimed.record(), true, null);
        }
        try (SqliteStorage again = storage()) {
            again.open(3);
            assertTrue(again.journal().unknown().isEmpty());
        }
    }

    @Test
    void aBoardSavedWithSaveNowIsOnDiskAtOnce() throws Exception {
        try (SqliteStorage first = storage()) {
            first.open(3);
            assertTrue(first.saveSlotNow(new Board.Saved(0, "kill_zombies", true, 1234L, List.of())));
        }
        try (SqliteStorage again = storage()) {
            SqliteStorage.Loaded loaded = again.open(3);
            assertEquals(1, loaded.slots().size());
            assertEquals("kill_zombies", loaded.slots().get(0).questId());
        }
    }

    @Test
    void theServerIdIsKeptInTheDatabase() throws Exception {
        try (SqliteStorage first = storage()) {
            first.open(3);
            assertNull(first.serverIdSlot().read());
            first.serverIdSlot().write("0a1b2c3d-1111-2222-3333-444455556666");
        }
        try (SqliteStorage again = storage()) {
            again.open(3);
            assertEquals("0a1b2c3d-1111-2222-3333-444455556666", again.serverIdSlot().read());
            assertFalse(again.newestBackup() != null);
        }
    }
}
