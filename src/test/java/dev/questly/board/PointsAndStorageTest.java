package dev.questly.board;

import dev.questly.storage.SqliteStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointsAndStorageTest {

    private static final UUID ALEX = UUID.nameUUIDFromBytes("alex".getBytes());
    private static final UUID SAM = UUID.nameUUIDFromBytes("sam".getBytes());

    @Test
    void pointsAreGivenAndSpent() {
        Points points = new Points();

        points.add(ALEX, "Alex", 10);
        assertEquals(10, points.get(ALEX));
        assertTrue(points.spend(ALEX, "Alex", 4));
        assertEquals(6, points.get(ALEX));
    }

    @Test
    void youCannotSpendMoreThanYouHave() {
        Points points = new Points();
        points.add(ALEX, "Alex", 5);

        assertFalse(points.spend(ALEX, "Alex", 6));
        assertFalse(points.spend(SAM, "Sam", 1));
        assertFalse(points.spend(ALEX, "Alex", -1));
        assertEquals(5, points.get(ALEX));
    }

    @Test
    void twoPurchasesOfTheLastPointsCannotBothWork() {
        Points points = new Points();
        points.add(ALEX, "Alex", 10);

        boolean first = points.spend(ALEX, "Alex", 10);
        boolean second = points.spend(ALEX, "Alex", 10);

        assertTrue(first);
        assertFalse(second);
        assertEquals(0, points.get(ALEX));
    }

    @Test
    void pointsNeverGoBelowZeroOrOverflow() {
        Points points = new Points();
        points.set(ALEX, "Alex", -5);
        assertEquals(0, points.get(ALEX));

        points.set(ALEX, "Alex", Integer.MAX_VALUE);
        points.add(ALEX, "Alex", 10);
        assertEquals(Integer.MAX_VALUE, points.get(ALEX));
    }

    @Test
    void everyChangeIsReportedSoItCanBeSaved() {
        Points points = new Points();
        List<String> seen = new ArrayList<>();
        points.listen((player, name, value) -> seen.add(name + "=" + value));

        points.add(ALEX, "Alex", 3);
        points.spend(ALEX, "Alex", 1);
        points.spend(ALEX, "Alex", 99);

        assertEquals(List.of("Alex=3", "Alex=2"), seen);
    }

    @Test
    void whatIsSavedCanBeReadBack(@TempDir Path folder) throws Exception {
        File file = folder.resolve("data.db").toFile();
        Logger log = Logger.getLogger("storage-test");

        try (SqliteStorage storage = new SqliteStorage(file, log)) {
            storage.open();
            storage.savePoints(ALEX, "Alex", 12);
            storage.savePoints(ALEX, "Alex", 15);
            storage.savePoints(SAM, "Sam", 3);
            storage.saveSlot(new Board.Saved(2, "quest-9", false, 12345L,
                    List.of(new Board.Score(ALEX, "Alex", 7.5), new Board.Score(SAM, "Sam", 1))));
            storage.saveSlot(new Board.Saved(2, "quest-9", false, 12345L, List.of(new Board.Score(SAM, "Sam", 2))));
        }

        try (SqliteStorage again = new SqliteStorage(file, log)) {
            SqliteStorage.Loaded loaded = again.open();

            assertEquals(2, loaded.points().size());
            assertEquals(15, loaded.points().stream().filter(p -> p.id().equals(ALEX)).findFirst().orElseThrow().points());
            assertEquals(1, loaded.slots().size());
            Board.Saved slot = loaded.slots().get(0);
            assertEquals(2, slot.slot());
            assertEquals("quest-9", slot.questId());
            assertFalse(slot.active());
            assertEquals(12345L, slot.startedAt());
            assertEquals(1, slot.scores().size());
            assertEquals(2.0, slot.scores().get(0).score());
        }
    }

    @Test
    void rewardsForAwayPlayersAreHandedOutOnce(@TempDir Path folder) throws Exception {
        try (SqliteStorage storage = new SqliteStorage(folder.resolve("data.db").toFile(), Logger.getLogger("storage-test"))) {
            storage.open();
            storage.addPending(ALEX, "give Alex diamond 1");
            storage.addPending(ALEX, "say hi");
            storage.addPending(SAM, "say sam");

            assertEquals(List.of("give Alex diamond 1", "say hi"), storage.takePending(ALEX).get());
            assertTrue(storage.takePending(ALEX).get().isEmpty());
            assertEquals(List.of("say sam"), storage.takePending(SAM).get());
        }
    }
}
