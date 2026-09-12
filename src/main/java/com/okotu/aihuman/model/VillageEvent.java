package com.okotu.aihuman.model;

import java.time.Instant;

/**
 * A shared piece of memory visible to every NPC in a village (village_events).
 */
public record VillageEvent(
        long id,
        String village,
        int priority,
        String summary,
        Instant expires
) {
}
