package dev.questly.quest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestTest {

    static Quest quest(String id, Trigger trigger, String extra, long required, double chance) {
        return new Quest(id, "Do %required% things", chance, trigger, extra, required, true, 2,
                new Quest.Display("STONE", "%title%", List.of()), List.of());
    }

    @Test
    void aQuestWithoutATargetTakesAnything() {
        Quest any = quest("1", Trigger.BREAK, null, 10, 50);

        assertTrue(any.matches("STONE"));
        assertTrue(any.matches("OAK_LOG"));
        assertTrue(quest("2", Trigger.CHAT, null, 10, 50).matches(null));
    }

    @Test
    void aQuestWithATargetOnlyTakesThatTarget() {
        Quest stone = quest("1", Trigger.BREAK, "STONE", 10, 50);

        assertTrue(stone.matches("STONE"));
        assertTrue(stone.matches("stone"));
        assertFalse(stone.matches("DEEPSLATE"));
        assertFalse(stone.matches(null));
    }

    @Test
    void anOreQuestAlsoTakesTheDeepslateOre() {
        Quest coal = quest("1", Trigger.BREAK, "COAL_ORE", 10, 50);

        assertTrue(coal.matches("COAL_ORE"));
        assertTrue(coal.matches("DEEPSLATE_COAL_ORE"));
        assertFalse(coal.matches("DEEPSLATE_IRON_ORE"));
    }

    @Test
    void theNetherOresAndOtherBlocksHaveNoDeepslateVersion() {
        assertFalse(quest("1", Trigger.BREAK, "NETHER_GOLD_ORE", 10, 50).matches("DEEPSLATE_NETHER_GOLD_ORE"));
        assertFalse(quest("2", Trigger.BREAK, "STONE", 10, 50).matches("DEEPSLATE_STONE"));
        assertFalse(quest("3", Trigger.KILL, "COAL_ORE", 10, 50).matches("DEEPSLATE_COAL_ORE"));
    }

    @Test
    void aFishingQuestWithoutATargetTakesFishButNotJunk() {
        Quest any = quest("1", Trigger.FISH_CAUGHT, null, 10, 50);

        assertTrue(any.matches("COD"));
        assertTrue(any.matches("SALMON"));
        assertTrue(any.matches("PUFFERFISH"));
        assertTrue(any.matches("TROPICAL_FISH"));
        assertFalse(any.matches("LEATHER_BOOTS"));
        assertFalse(any.matches("FISHING_ROD"));
        assertFalse(any.matches(null));
    }

    @Test
    void aFishingQuestWithATargetTakesOnlyThatFish() {
        Quest cod = quest("1", Trigger.FISH_CAUGHT, "COD", 10, 50);

        assertTrue(cod.matches("COD"));
        assertFalse(cod.matches("SALMON"));
    }

    @Test
    void theTitleHasTheAmountFilledIn() {
        assertEquals("Do 75 things", quest("1", Trigger.KILL, "ZOMBIE", 75, 50).titleText());
    }

    // ---- picking ----

    @Test
    void aBiggerChanceIsPickedMoreOften() {
        QuestLibrary library = new QuestLibrary(List.of(quest("rare", Trigger.KILL, "A", 5, 1), quest("common", Trigger.KILL, "B", 5, 99)));
        Random random = new Random(1);
        int common = 0;
        for (int i = 0; i < 1000; i++) {
            if (library.pick(random, List.of(), false).id().equals("common")) common++;
        }

        assertTrue(common > 900, "common was picked " + common + " times");
    }

    @Test
    void aQuestThatIsAlreadyUpIsNotPickedAgain() {
        Quest a = quest("a", Trigger.KILL, "A", 5, 50);
        QuestLibrary library = new QuestLibrary(List.of(a, quest("b", Trigger.KILL, "B", 5, 50)));

        for (int i = 0; i < 50; i++) {
            assertEquals("b", library.pick(new Random(i), List.of(a), true).id());
        }
    }

    @Test
    void aQuestThatAsksForTheSameThingIsAvoided() {
        Quest zombies15 = quest("z15", Trigger.KILL, "ZOMBIE", 15, 50);
        QuestLibrary library = new QuestLibrary(List.of(zombies15, quest("z40", Trigger.KILL, "ZOMBIE", 40, 50), quest("spider", Trigger.KILL, "SPIDER", 15, 50)));

        for (int i = 0; i < 50; i++) {
            assertEquals("spider", library.pick(new Random(i), List.of(zombies15), true).id());
        }
    }

    @Test
    void whenNothingNewIsLeftASimilarQuestIsBetterThanNone() {
        Quest zombies15 = quest("z15", Trigger.KILL, "ZOMBIE", 15, 50);
        QuestLibrary library = new QuestLibrary(List.of(zombies15, quest("z40", Trigger.KILL, "ZOMBIE", 40, 50)));

        assertEquals("z40", library.pick(new Random(1), List.of(zombies15), true).id());
        assertNotEquals(null, library.pick(new Random(1), List.of(zombies15, library.get("z40")), true));
    }

    @Test
    void anEmptyLibraryPicksNothing() {
        assertNull(new QuestLibrary(List.of()).pick(new Random(1), List.of(), true));
    }
}
