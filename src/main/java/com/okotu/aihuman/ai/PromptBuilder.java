package com.okotu.aihuman.ai;

import com.okotu.aihuman.model.ConversationEntry;
import com.okotu.aihuman.model.KnowledgeEntry;
import com.okotu.aihuman.model.NpcProfile;
import com.okotu.aihuman.model.NpcState;
import com.okotu.aihuman.model.PlayerMemory;
import com.okotu.aihuman.model.VillageEvent;
import com.okotu.aihuman.service.RelationshipService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Assembles the system prompt sent to Ollama out of everything the plugin
 * knows: the NPC's character sheet, what it remembers about this specific
 * player, what it knows about the world, current village events + emotional
 * state, and the last few raw messages. Aims to stay in the 300-500 token
 * range described in the design doc, small enough for a 1B-1.5B model.
 *
 * Sections: SYSTEM / MEMORY / KNOWLEDGE / CONTEXT. The final "RECENT
 * MESSAGES" part is NOT included in the system prompt text - it's passed
 * separately to OllamaClient as chat history, since that maps more
 * naturally onto Ollama's /api/chat message list than being flattened into
 * the system message text.
 */
public class PromptBuilder {

    private static final String SEPARATOR =
            "-------------------------------------------------";

    private final RelationshipService relationshipService;

    public PromptBuilder(RelationshipService relationshipService) {
        this.relationshipService = relationshipService;
    }

    public String buildSystemPrompt(NpcProfile profile, PlayerMemory memory,
                                     List<KnowledgeEntry> knowledge, List<VillageEvent> villageEvents,
                                     NpcState state) {
        StringBuilder sb = new StringBuilder();

        sb.append("SYSTEM\n").append(buildCharacterSection(profile)).append('\n');

        String memorySection = buildMemorySection(memory);
        if (!memorySection.isBlank()) {
            sb.append(SEPARATOR).append("\nMEMORY\n").append(memorySection).append('\n');
        }

        String knowledgeSection = buildKnowledgeSection(knowledge);
        if (!knowledgeSection.isBlank()) {
            sb.append(SEPARATOR).append("\nKNOWLEDGE\n").append(knowledgeSection).append('\n');
            sb.append("Only talk about what you know. If asked about a topic that isn't in this "
                    + "list, admit you don't know instead of making up details.\n");
        }

        String contextSection = buildContextSection(villageEvents, state);
        if (!contextSection.isBlank()) {
            sb.append(SEPARATOR).append("\nCONTEXT\n").append(contextSection);
        }

        return sb.toString().stripTrailing();
    }

    public List<OllamaClient.ChatMessage> buildHistory(List<ConversationEntry> entries) {
        return entries.stream()
                .map(e -> e.role() == ConversationEntry.Role.PLAYER
                        ? OllamaClient.ChatMessage.user(e.message())
                        : OllamaClient.ChatMessage.assistant(e.message()))
                .collect(Collectors.toList());
    }

    // ---------------------------------------------------------------
    // SYSTEM: the character sheet
    // ---------------------------------------------------------------
    private String buildCharacterSection(NpcProfile profile) {
        // If an explicit system_prompt was authored for this NPC, it fully
        // overrides the auto-built version below.
        if (profile.systemPrompt() != null && !profile.systemPrompt().isBlank()) {
            return profile.systemPrompt().strip();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("You are ").append(profile.name()).append(".\n");
        if (notBlank(profile.profession())) {
            sb.append(profile.profession()).append(".\n");
        } else if (notBlank(profile.role())) {
            sb.append(profile.role()).append(".\n");
        }
        if (notBlank(profile.village())) {
            sb.append("You live in the village of ").append(profile.village()).append(".\n");
        }
        if (notBlank(profile.personality())) {
            sb.append("Personality: ").append(profile.personality()).append("\n");
        }
        if (notBlank(profile.background())) {
            sb.append("Background: ").append(profile.background()).append("\n");
        }
        if (notBlank(profile.speechStyle())) {
            sb.append(profile.speechStyle()).append("\n");
        }
        if (notBlank(profile.knowledge())) {
            sb.append(profile.knowledge()).append("\n");
        }
        sb.append("Always stay in character. Answer in English, briefly "
                + "(2-3 sentences max), in a way that suits a game chat.");
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // MEMORY: what this NPC remembers about this specific player
    // ---------------------------------------------------------------
    private String buildMemorySection(PlayerMemory memory) {
        if (memory == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String playerLabel = notBlank(memory.knownName()) ? memory.knownName() : "this player";
        sb.append("You know ").append(playerLabel).append(".\n");
        sb.append("How you feel about them: ").append(relationshipService.describe(memory.relationshipScore()))
                .append(" (score ").append(memory.relationshipScore()).append("/100).\n");
        if (notBlank(memory.summary())) {
            sb.append(memory.summary().strip()).append("\n");
        }
        if (notBlank(memory.notes())) {
            sb.append("Notes: ").append(memory.notes().strip()).append("\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // KNOWLEDGE: what this NPC knows (and, implicitly, doesn't know)
    // ---------------------------------------------------------------
    private String buildKnowledgeSection(List<KnowledgeEntry> knowledge) {
        if (knowledge == null || knowledge.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeEntry entry : knowledge) {
            sb.append("- ").append(entry.topic()).append(": ").append(entry.text()).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // CONTEXT: shared village events + current emotional state
    // ---------------------------------------------------------------
    private String buildContextSection(List<VillageEvent> villageEvents, NpcState state) {
        StringBuilder sb = new StringBuilder();
        if (villageEvents != null) {
            for (VillageEvent event : villageEvents) {
                sb.append("- ").append(event.summary()).append('\n');
            }
        }
        String mood = describeMood(state);
        if (!mood.isBlank()) {
            sb.append(mood);
        }
        return sb.toString();
    }

    private String describeMood(NpcState state) {
        if (state == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (state.fatigue() >= 60) sb.append("You're tired today.\n");
        if (state.hunger() >= 60) sb.append("You're hungry.\n");
        if (state.fear() >= 60) sb.append("You're scared.\n");
        if (state.anger() >= 60) sb.append("You're angry.\n");
        if (state.happiness() >= 80) sb.append("You're in a great mood.\n");
        else if (state.happiness() <= 20) sb.append("You're in a bad mood.\n");
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
