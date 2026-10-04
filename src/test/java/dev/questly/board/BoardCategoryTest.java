package dev.questly.board;

import dev.questly.quest.Category;
import dev.questly.quest.Quest;
import dev.questly.quest.QuestLibrary;
import dev.questly.quest.Trigger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BoardCategoryTest {

    private static final UUID ALEX = UUID.nameUUIDFromBytes("alex".getBytes());

    private static Quest quest(String id, Trigger trigger, String extra) {
        return new Quest(id, "Do %required% things", 50, trigger, extra, 5, true, 1,
                new Quest.Display("STONE", "%title%", List.of()), List.of());
    }

    private static Board board(Quest... quests) {
        Board board = new Board(new QuestLibrary(List.of(quests)), new Random(1), () -> 1_000_000L);
        board.configure(List.of(Category.COMBAT, Category.MINING, Category.FISHING), 2, 60, false);
        board.fill();
        return board;
    }

    @Test
    void everyCategoryGetsItsOwnSlotsWithQuestsOfThatCategory() {
        Board board = board(quest("zombie", Trigger.KILL, "ZOMBIE"), quest("stone", Trigger.BREAK, "STONE"));

        assertEquals(6, board.slots().size());
        assertEquals(2, board.slotsOf(Category.COMBAT).size());
        for (Board.Slot slot : board.slotsOf(Category.COMBAT)) assertEquals("zombie", slot.quest().id());
        for (Board.Slot slot : board.slotsOf(Category.MINING)) assertEquals("stone", slot.quest().id());
        // there is no fishing quest, so those slots stay empty instead of taking another category
        for (Board.Slot slot : board.slotsOf(Category.FISHING)) assertNull(slot.quest());
    }

    @Test
    void savedQuestsMoveToTheSlotsOfTheirCategory() {
        Quest zombie = quest("zombie", Trigger.KILL, "ZOMBIE");
        Quest stone = quest("stone", Trigger.BREAK, "STONE");
        Board board = new Board(new QuestLibrary(List.of(zombie, stone)), new Random(1), () -> 1_000_000L);
        board.configure(List.of(Category.COMBAT, Category.MINING), 3, 60, false);

        // a board saved before the categories: both quests in the first slots
        board.restore(List.of(
                new Board.Saved(0, "stone", true, 5, List.of(new Board.Score(ALEX, "Alex", 4))),
                new Board.Saved(1, "zombie", true, 6, List.of())));

        Board.Slot mining = board.slotsOf(Category.MINING).get(0);
        assertEquals("stone", mining.quest().id());
        assertEquals(4, mining.scoreOf(ALEX));
        assertEquals("zombie", board.slotsOf(Category.COMBAT).stream().filter(s -> s.quest().id().equals("zombie")).findFirst().orElseThrow().quest().id());
    }

    @Test
    void progressStaysWhenTheLayoutGrows() {
        Board board = board(quest("zombie", Trigger.KILL, "ZOMBIE"), quest("stone", Trigger.BREAK, "STONE"));
        board.record(ALEX, "Alex", Trigger.BREAK, "STONE", 3, true);
        Board.Slot before = board.slotsOf(Category.MINING).get(0);
        assertEquals(3, before.scoreOf(ALEX));

        board.configure(List.of(Category.COMBAT, Category.MINING, Category.FISHING), 4, 60, false);

        assertEquals(12, board.slots().size());
        assertEquals(3, board.slotsOf(Category.MINING).stream().mapToDouble(s -> s.scoreOf(ALEX)).max().orElse(0));
        assertNotNull(board.slotsOf(Category.COMBAT).get(0).quest());
    }
}
