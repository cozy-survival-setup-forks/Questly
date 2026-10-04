package dev.questly.quest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CategoryTest {

    @Test
    void aQuestGetsItsCategoryFromWhatItAsksFor() {
        assertEquals(Category.COMBAT, Category.infer(Trigger.KILL, "ZOMBIE"));
        assertEquals(Category.COMBAT, Category.infer(Trigger.KILL, null));
        assertEquals(Category.ANIMALS, Category.infer(Trigger.KILL, "COW"));
        assertEquals(Category.MINING, Category.infer(Trigger.BREAK, "DIAMOND_ORE"));
        assertEquals(Category.MINING, Category.infer(Trigger.BREAK, null));
        assertEquals(Category.FARMING, Category.infer(Trigger.BREAK, "WHEAT"));
        assertEquals(Category.FARMING, Category.infer(Trigger.BREAK, "OAK_LOG"));
        assertEquals(Category.ANIMALS, Category.infer(Trigger.BREED_ENTITY, "PIG"));
        assertEquals(Category.ANIMALS, Category.infer(Trigger.TAME_ENTITY, "WOLF"));
        assertEquals(Category.FISHING, Category.infer(Trigger.FISH_CAUGHT, null));
        assertEquals(Category.EXPLORATION, Category.infer(Trigger.WALK, null));
        assertEquals(Category.EXPLORATION, Category.infer(Trigger.RIDE_VEHICLE, "OAK_BOAT"));
        assertEquals(Category.MISC, Category.infer(Trigger.CHAT, null));
        assertEquals(Category.MISC, Category.infer(Trigger.ENCHANT_ITEM, null));
    }

    @Test
    void everyTriggerHasACategory() {
        for (Trigger trigger : Trigger.values()) {
            Category.infer(trigger, null);
        }
    }

    @Test
    void namesAreParsedByKeyOrByName() {
        assertEquals(Category.ANIMALS, Category.parse("animals"));
        assertEquals(Category.MISC, Category.parse(" MISC "));
        assertEquals(Category.EXPLORATION, Category.parse("Exploration"));
        assertNull(Category.parse("pets"));
        assertNull(Category.parse(null));
    }

    @Test
    void aLibraryPicksOnlyFromTheAskedCategory() {
        Quest combat = new Quest("a", "t", 50, Trigger.KILL, "ZOMBIE", 5, true, 1,
                new Quest.Display("STONE", "x", List.of()), List.of());
        Quest mining = new Quest("b", "t", 50, Trigger.BREAK, "STONE", 5, true, 1,
                new Quest.Display("STONE", "x", List.of()), List.of());
        QuestLibrary library = new QuestLibrary(List.of(combat, mining));

        for (int i = 0; i < 20; i++) {
            assertEquals("a", library.pick(new Random(i), List.of(), true, Category.COMBAT).id());
            assertEquals("b", library.pick(new Random(i), List.of(), true, Category.MINING).id());
        }
        assertNull(library.pick(new Random(1), List.of(), true, Category.FISHING));
    }
}
