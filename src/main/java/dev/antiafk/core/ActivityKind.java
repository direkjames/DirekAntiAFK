package dev.antiafk.core;

import java.util.Locale;

/** Every kind of activity the plugin tracks. */
public enum ActivityKind {
    // Real input from a person at the keyboard. Ends AFK.
    LOOK(false), MOVE(false), CHAT(false), CHECK(false),
    // Things an auto-clicker or a macro can produce on its own. They keep an active player active for a
    // while, but don't end AFK and stop counting once the player hasn't looked around or moved for a while.
    BREAK(true), PLACE(true), INTERACT(true), ATTACK(true), FISH(true), EAT(true), DROP(true),
    INVENTORY(true), COMMAND(true),
    // Set by the plugin itself.
    JOIN(false), EXEMPT(false);

    /** True for click-type activity a macro can produce without any real input. */
    public final boolean clickOnly;
    public final String label;

    ActivityKind(boolean clickOnly) {
        this.clickOnly = clickOnly;
        this.label = name().toLowerCase(Locale.ROOT);
    }
}
