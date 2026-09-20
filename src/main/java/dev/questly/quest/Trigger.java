package dev.questly.quest;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** The things a quest can ask a player to do. */
public enum Trigger {
    KILL(Target.ENTITY),
    BREAK(Target.BLOCK),
    BREED_ENTITY(Target.ENTITY),
    TAME_ENTITY(Target.ENTITY),
    FISH_CAUGHT(Target.ITEM),
    CHAT(Target.NONE),
    ENCHANT_ITEM(Target.NONE),
    WALK(Target.NONE),
    SPRINT(Target.NONE),
    SWIM(Target.NONE),
    AVIATE(Target.NONE),
    RIDE_VEHICLE(Target.ENTITY);

    /** What the optional {@code extra} of a quest names. */
    public enum Target {
        NONE, ENTITY, BLOCK, ITEM
    }

    private final Target target;

    Trigger(Target target) {
        this.target = target;
    }

    public Target target() {
        return target;
    }

    /** True for the quests that count blocks travelled instead of single actions. */
    public boolean measuresDistance() {
        return this == WALK || this == SPRINT || this == SWIM || this == AVIATE || this == RIDE_VEHICLE;
    }

    public static @Nullable Trigger parse(@Nullable String name) {
        if (name == null) return null;
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
