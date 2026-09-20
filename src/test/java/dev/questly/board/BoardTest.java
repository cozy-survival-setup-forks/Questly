package dev.questly.board;

import dev.questly.quest.Quest;
import dev.questly.quest.QuestLibrary;
import dev.questly.quest.Trigger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardTest {

    private static final UUID ALEX = UUID.nameUUIDFromBytes("alex".getBytes());
    private static final UUID SAM = UUID.nameUUIDFromBytes("sam".getBytes());

    private final AtomicLong clock = new AtomicLong(1_000_000);

    private static Quest quest(String id, Trigger trigger, String extra, long required, boolean prevent, int points) {
        return new Quest(id, "Do %required% things", 50, trigger, extra, required, prevent, points,
                new Quest.Display("STONE", "%title%", List.of()), List.of(new Quest.Reward(1, List.of("say hi %player%"))));
    }

    /** A board with one slot that has exactly the given quest. */
    private Board boardWith(Quest quest) {
        Board board = new Board(new QuestLibrary(List.of(quest)), new Random(1), clock::get);
        board.configure(1, 60, true);
        board.fill();
        return board;
    }

    private static Board.Result kill(Board board, UUID who, String name, String mob, double amount) {
        return board.record(who, name, Trigger.KILL, mob, amount, true);
    }

    @Test
    void theBoardFillsItsSlotsWithQuests() {
        Board board = new Board(new QuestLibrary(List.of(quest("a", Trigger.KILL, "A", 5, true, 1), quest("b", Trigger.KILL, "B", 5, true, 1))), new Random(1), clock::get);
        board.configure(2, 60, true);

        assertEquals(2, board.fill().size());
        assertNotNull(board.slot(0).quest());
        assertNotNull(board.slot(1).quest());
        assertFalse(board.slot(0).quest().id().equals(board.slot(1).quest().id()));
    }

    @Test
    void progressAddsUpForThePlayerWhoDidIt() {
        Board board = boardWith(quest("a", Trigger.KILL, "ZOMBIE", 5, true, 1));

        kill(board, ALEX, "Alex", "ZOMBIE", 1);
        kill(board, ALEX, "Alex", "ZOMBIE", 1);
        kill(board, SAM, "Sam", "ZOMBIE", 1);

        assertEquals(2, board.slot(0).scoreOf(ALEX));
        assertEquals(1, board.slot(0).scoreOf(SAM));
    }

    @Test
    void thingsThatAreNotTheTargetDoNotCount() {
        Board board = boardWith(quest("a", Trigger.KILL, "ZOMBIE", 5, true, 1));

        kill(board, ALEX, "Alex", "SPIDER", 1);
        board.record(ALEX, "Alex", Trigger.BREAK, "ZOMBIE", 1, true);

        assertEquals(0, board.slot(0).scoreOf(ALEX));
    }

    @Test
    void theFirstPlayerToReachTheAmountWins() {
        Board board = boardWith(quest("a", Trigger.KILL, "ZOMBIE", 3, true, 2));

        kill(board, SAM, "Sam", "ZOMBIE", 1);
        kill(board, ALEX, "Alex", "ZOMBIE", 1);
        kill(board, ALEX, "Alex", "ZOMBIE", 1);
        Board.Result result = kill(board, ALEX, "Alex", "ZOMBIE", 1);

        assertEquals(1, result.completions().size());
        Board.Completion won = result.completions().get(0);
        assertEquals(ALEX, won.winner());
        assertEquals("Alex", won.winnerName());
        assertEquals(List.of(ALEX, SAM), won.ranking().stream().map(Board.Entry::id).toList());
        assertFalse(board.slot(0).active());
    }

    @Test
    void aQuestThatWasWonTakesNoMoreProgressAndCannotBeWonTwice() {
        Board board = boardWith(quest("a", Trigger.KILL, "ZOMBIE", 1, true, 2));

        assertEquals(1, kill(board, ALEX, "Alex", "ZOMBIE", 1).completions().size());
        assertEquals(0, kill(board, SAM, "Sam", "ZOMBIE", 1).completions().size());
        assertEquals(0, board.slot(0).scoreOf(SAM));
    }

    @Test
    void oneBigStepCanFinishAQuest() {
        Board board = boardWith(quest("a", Trigger.WALK, null, 100, true, 1));

        Board.Result result = board.record(ALEX, "Alex", Trigger.WALK, null, 250.5, true);

        assertEquals(1, result.completions().size());
    }

    @Test
    void cheatingDoesNotCountWhenTheQuestPreventsIt() {
        Board board = boardWith(quest("a", Trigger.BREAK, "STONE", 5, true, 1));

        board.record(ALEX, "Alex", Trigger.BREAK, "STONE", 1, false);

        assertEquals(0, board.slot(0).scoreOf(ALEX));
    }

    @Test
    void cheatingCountsWhenTheQuestDoesNotPreventIt() {
        Board board = boardWith(quest("a", Trigger.BREAK, "STONE", 5, false, 1));

        board.record(ALEX, "Alex", Trigger.BREAK, "STONE", 1, false);

        assertEquals(1, board.slot(0).scoreOf(ALEX));
    }

    @Test
    void nothingIsCountedForZeroOrLessAndNoQuestAsksForIt() {
        Board board = boardWith(quest("a", Trigger.KILL, "ZOMBIE", 5, true, 1));

        assertTrue(kill(board, ALEX, "Alex", "ZOMBIE", 0).completions().isEmpty());
        assertTrue(kill(board, ALEX, "Alex", "ZOMBIE", -3).completions().isEmpty());
        assertEquals(0, board.slot(0).scoreOf(ALEX));
        assertTrue(board.wants(Trigger.KILL));
        assertFalse(board.wants(Trigger.CHAT));
    }

    @Test
    void passingTheLeaderIsReportedOnce() {
        Board board = boardWith(quest("a", Trigger.KILL, "ZOMBIE", 10, true, 1));

        kill(board, ALEX, "Alex", "ZOMBIE", 2);
        Board.Result tie = kill(board, SAM, "Sam", "ZOMBIE", 2);
        Board.Result pass = kill(board, SAM, "Sam", "ZOMBIE", 1);
        Board.Result again = kill(board, SAM, "Sam", "ZOMBIE", 1);

        assertTrue(tie.takeovers().isEmpty());
        assertEquals(1, pass.takeovers().size());
        assertEquals(ALEX, pass.takeovers().get(0).previousLeader());
        assertEquals("Sam", pass.takeovers().get(0).newLeaderName());
        assertTrue(again.takeovers().isEmpty());
    }

    // ---- resting and new quests ----

    @Test
    void aWonSlotRestsThenGetsANewQuest() {
        Board board = new Board(new QuestLibrary(List.of(quest("a", Trigger.KILL, "A", 1, true, 1), quest("b", Trigger.KILL, "B", 1, true, 1))), new Random(1), clock::get);
        board.configure(1, 60, true);
        board.fill();
        String first = board.slot(0).quest().id();
        board.record(ALEX, "Alex", Trigger.KILL, first.equals("a") ? "A" : "B", 1, true);

        assertTrue(board.tick().isEmpty());
        assertEquals(60_000, board.restLeft(board.slot(0)));

        clock.addAndGet(59_000);
        assertTrue(board.tick().isEmpty());
        clock.addAndGet(1_000);
        assertEquals(1, board.tick().size());
        assertTrue(board.slot(0).active());
        assertEquals(0, board.slot(0).scoreOf(ALEX));
    }

    @Test
    void anActiveSlotNeverGetsReplacedByTheTimer() {
        Board board = boardWith(quest("a", Trigger.KILL, "A", 5, true, 1));
        clock.addAndGet(10_000_000);

        assertTrue(board.tick().isEmpty());
    }

    @Test
    void anAdminCanSetAQuestAndResetTheBoard() {
        Quest a = quest("a", Trigger.KILL, "A", 5, true, 1);
        Quest b = quest("b", Trigger.KILL, "B", 5, true, 1);
        Board board = new Board(new QuestLibrary(List.of(a, b)), new Random(1), clock::get);
        board.configure(1, 60, true);
        board.fill();
        kill(board, ALEX, "Alex", board.slot(0).quest().extra(), 2);

        assertTrue(board.set(0, b));
        assertEquals("b", board.slot(0).quest().id());
        assertEquals(0, board.slot(0).scoreOf(ALEX));
        assertFalse(board.set(5, b));

        kill(board, ALEX, "Alex", "B", 2);
        board.reset();
        assertEquals(0, board.slot(0).scoreOf(ALEX));
    }

    // ---- saving ----

    @Test
    void theBoardCanBeSavedAndRestored() {
        Quest a = quest("a", Trigger.KILL, "ZOMBIE", 10, true, 1);
        Board board = boardWith(a);
        kill(board, ALEX, "Alex", "ZOMBIE", 4);
        kill(board, SAM, "Sam", "ZOMBIE", 2);
        Board.Saved saved = board.snapshot(board.slot(0));

        Board restored = new Board(new QuestLibrary(List.of(a)), new Random(2), clock::get);
        restored.configure(1, 60, true);
        restored.restore(List.of(saved));

        assertEquals("a", restored.slot(0).quest().id());
        assertEquals(4, restored.slot(0).scoreOf(ALEX));
        assertEquals(2, restored.slot(0).scoreOf(SAM));
        assertEquals("Alex", restored.slot(0).ranking().get(0).name());
    }

    @Test
    void aSavedQuestThatIsGoneGetsReplaced() {
        Quest a = quest("a", Trigger.KILL, "ZOMBIE", 10, true, 1);
        Board board = new Board(new QuestLibrary(List.of(a)), new Random(1), clock::get);
        board.configure(1, 60, true);

        board.restore(List.of(new Board.Saved(0, "deleted", true, 0, List.of(new Board.Score(ALEX, "Alex", 5)))));

        assertEquals("a", board.slot(0).quest().id());
        assertEquals(0, board.slot(0).scoreOf(ALEX));
    }

    @Test
    void reloadingKeepsProgressOfQuestsThatStillExist() {
        Quest a = quest("a", Trigger.KILL, "ZOMBIE", 10, true, 1);
        Board board = boardWith(a);
        kill(board, ALEX, "Alex", "ZOMBIE", 3);

        board.setLibrary(new QuestLibrary(List.of(quest("a", Trigger.KILL, "ZOMBIE", 20, true, 5))));
        assertEquals(3, board.slot(0).scoreOf(ALEX));
        assertEquals(20, board.slot(0).quest().required());

        board.setLibrary(new QuestLibrary(List.of(quest("z", Trigger.KILL, "SPIDER", 5, true, 1))));
        assertNull(board.slot(0).quest());
        board.fill();
        assertEquals("z", board.slot(0).quest().id());
        assertEquals(0, board.slot(0).scoreOf(ALEX));
    }

    @Test
    void theNumberOfSlotsCanChange() {
        Board board = boardWith(quest("a", Trigger.KILL, "A", 5, true, 1));

        board.configure(3, 60, false);
        assertEquals(3, board.slots().size());
        board.configure(1, 60, false);
        assertEquals(1, board.slots().size());
    }
}
