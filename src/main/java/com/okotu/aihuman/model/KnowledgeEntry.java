package com.okotu.aihuman.model;

/**
 * A single fact an NPC knows about a topic (npc_knowledge). An NPC should
 * only talk about topics it has an entry for.
 */
public record KnowledgeEntry(int npcId, String topic, String text) {
}
