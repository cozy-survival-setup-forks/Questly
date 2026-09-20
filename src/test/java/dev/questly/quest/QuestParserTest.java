package dev.questly.quest;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestParserTest {

    private static final QuestParser.Names ANYTHING = new QuestParser.Names() {
        @Override
        public boolean entity(String name) {
            return true;
        }

        @Override
        public boolean block(String name) {
            return true;
        }

        @Override
        public boolean item(String name) {
            return true;
        }
    };

    private static List<Quest> parse(String text, List<String> warnings) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        Logger log = new Logger("test", null) {
            @Override
            public void warning(String msg) {
                warnings.add(msg);
            }
        };
        return QuestParser.parse(yaml, log, ANYTHING);
    }

    @Test
    void aQuestIsReadWithAllItsSettings() throws Exception {
        List<String> warnings = new ArrayList<>();
        List<Quest> quests = parse("""
                quests:
                  '7':
                    title: Kill %required% Creepers
                    chance: 30
                    settings:
                      trigger: KILL
                      extra: creeper
                      required: 75
                      prevent-cheating: false
                      win-points: 4
                      rewards:
                        1:
                          commands: ["say %player% won"]
                    display-item:
                      material: creeper_head
                      name: '&aHi %title%'
                      lore: ['one', 'two']
                """, warnings);

        assertEquals(1, quests.size());
        Quest quest = quests.get(0);
        assertEquals("7", quest.id());
        assertEquals(Trigger.KILL, quest.trigger());
        assertEquals("CREEPER", quest.extra());
        assertEquals(75, quest.required());
        assertFalse(quest.preventCheating());
        assertEquals(4, quest.winPoints());
        assertEquals("Kill 75 Creepers", quest.titleText());
        assertEquals("CREEPER_HEAD", quest.display().material());
        assertEquals(List.of("one", "two"), quest.display().lore());
        assertEquals(1, quest.rewards().size());
        assertEquals(List.of("say %player% won"), quest.rewards().get(0).commands());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void brokenQuestsAreSkippedAndTheRestStay() throws Exception {
        List<String> warnings = new ArrayList<>();
        List<Quest> quests = parse("""
                quests:
                  a:
                    settings: {trigger: JUMP, required: 5}
                  b:
                    settings: {trigger: KILL, required: 0}
                  c:
                    title: no settings
                  d:
                    settings: {trigger: BREAK, required: 3}
                """, warnings);

        assertEquals(List.of("d"), quests.stream().map(Quest::id).toList());
        assertEquals(3, warnings.size(), warnings.toString());
    }

    @Test
    void namesThatDoNotExistAreRefused() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("quests:\n  a:\n    settings: {trigger: KILL, extra: NOTAMOB, required: 5}\n  b:\n    settings: {trigger: KILL, extra: ZOMBIE, required: 5}\n");
        QuestParser.Names strict = new QuestParser.Names() {
            @Override
            public boolean entity(String name) {
                return name.equals("ZOMBIE");
            }

            @Override
            public boolean block(String name) {
                return true;
            }

            @Override
            public boolean item(String name) {
                return true;
            }
        };

        List<Quest> quests = QuestParser.parse(yaml, Logger.getLogger("quiet"), strict);

        assertEquals(List.of("b"), quests.stream().map(Quest::id).toList());
    }

    @Test
    void anExtraOnAQuestThatDoesNotUseOneIsIgnored() throws Exception {
        List<String> warnings = new ArrayList<>();
        List<Quest> quests = parse("quests:\n  a:\n    settings: {trigger: CHAT, extra: HELLO, required: 5}\n", warnings);

        assertNull(quests.get(0).extra());
        assertEquals(1, warnings.size());
    }

    // ---- the quests that come with the plugin ----

    private static List<Quest> shipped(List<String> warnings) throws Exception {
        var stream = QuestParserTest.class.getResourceAsStream("/quests.yml");
        assertNotNull(stream);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        Logger log = new Logger("shipped", null) {
            @Override
            public void warning(String msg) {
                warnings.add(msg);
            }
        };
        return QuestParser.parse(yaml, log, ANYTHING);
    }

    @Test
    void allThreeHundredShippedQuestsLoadWithoutAProblem() throws Exception {
        List<String> warnings = new ArrayList<>();
        List<Quest> quests = shipped(warnings);

        assertEquals(300, quests.size());
        assertTrue(warnings.isEmpty(), warnings.toString());

        Set<String> ids = new HashSet<>();
        for (Quest quest : quests) {
            assertTrue(ids.add(quest.id()), "duplicate id " + quest.id());
            assertTrue(quest.required() >= 1, quest.id());
            assertTrue(quest.winPoints() >= 1, quest.id());
            assertTrue(quest.preventCheating(), quest.id());
            assertFalse(quest.display().lore().isEmpty(), quest.id());
            assertTrue(quest.title().contains("%required%"), quest.id());
        }
    }

    @Test
    void everyKindOfTriggerIsUsedByTheShippedQuests() throws Exception {
        Map<Trigger, Integer> counts = new EnumMap<>(Trigger.class);
        for (Quest quest : shipped(new ArrayList<>())) counts.merge(quest.trigger(), 1, Integer::sum);

        for (Trigger trigger : Trigger.values()) {
            assertTrue(counts.containsKey(trigger), trigger + " has no quest");
        }
        assertEquals(125, counts.get(Trigger.BREAK));
        assertEquals(119, counts.get(Trigger.KILL));
    }

    @Test
    void theBoatQuestNamesTheBoatItAsksFor() throws Exception {
        Quest boat = shipped(new ArrayList<>()).stream().filter(quest -> quest.trigger() == Trigger.RIDE_VEHICLE).findFirst().orElseThrow();

        assertEquals("OAK_BOAT", boat.extra());
        assertTrue(boat.matches("OAK_BOAT"));
        assertFalse(boat.matches("OAK_CHEST_BOAT"));
        assertFalse(boat.matches("MINECART"));
    }

    @Test
    void questsWithATargetHaveOneThatMatchesTheirOwnName() throws Exception {
        for (Quest quest : shipped(new ArrayList<>())) {
            if (quest.extra() != null) assertTrue(quest.matches(quest.extra()), quest.id());
        }
    }
}
