package com.okotu.aihuman.model;

import java.time.Instant;

/**
 * A single line of dialogue, from either a player or the NPC.
 */
public record ConversationEntry(Role role, String message, Instant timestamp) {

    public enum Role {
        PLAYER("player"),
        NPC("npc");

        private final String dbValue;

        Role(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static Role fromDb(String value) {
            for (Role r : values()) {
                if (r.dbValue.equalsIgnoreCase(value)) {
                    return r;
                }
            }
            throw new IllegalArgumentException("Unknown role: " + value);
        }
    }

    public static ConversationEntry player(String message) {
        return new ConversationEntry(Role.PLAYER, message, Instant.now());
    }

    public static ConversationEntry npc(String message) {
        return new ConversationEntry(Role.NPC, message, Instant.now());
    }
}
