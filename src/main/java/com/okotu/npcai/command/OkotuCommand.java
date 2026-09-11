package com.okotu.npcai.command;

import com.okotu.npcai.OkotuNpcAiPlugin;
import com.okotu.npcai.db.KnowledgeDao;
import com.okotu.npcai.db.NpcProfileDao;
import com.okotu.npcai.db.NpcStateDao;
import com.okotu.npcai.db.PlayerMemoryDao;
import com.okotu.npcai.db.VillageEventDao;
import com.okotu.npcai.model.KnowledgeEntry;
import com.okotu.npcai.model.NpcProfile;
import com.okotu.npcai.model.NpcState;
import com.okotu.npcai.model.PlayerMemory;
import com.okotu.npcai.model.VillageEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public class OkotuCommand implements CommandExecutor {

    private static final List<String> PROFILE_FIELDS = List.of(
            "name", "role", "personality", "background", "village",
            "profession", "speech_style", "knowledge", "system_prompt");

    private final OkotuNpcAiPlugin plugin;

    public OkotuCommand(OkotuNpcAiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadPlugin();
                sender.sendMessage(ChatColor.GREEN + "okotu-npc-ai-engine: configuration reloaded.");
            }
            case "profile" -> handleProfile(sender, args);
            case "knowledge" -> handleKnowledge(sender, args);
            case "event" -> handleEvent(sender, args);
            case "relationship" -> handleRelationship(sender, args);
            case "state" -> handleState(sender, args);
            case "enable" -> handleEnable(sender, args);
            case "disable" -> handleDisable(sender, args);
            case "version" -> handleVersion(sender);
            case "info" -> handleInfo(sender, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "/okotunpc reload");
        sender.sendMessage(ChatColor.YELLOW + "/okotunpc profile <npcId> <field> <value...>  "
                + "(fields: " + String.join(", ", PROFILE_FIELDS) + ")");
        sender.sendMessage(ChatColor.YELLOW + "/okotunpc knowledge add|remove <npcId> <topic> [text...]");
        sender.sendMessage(ChatColor.YELLOW + "/okotunpc event add <village> <priority> <expiresHours|never> <summary...>");
        sender.sendMessage(ChatColor.YELLOW + "/okotunpc event remove <eventId>");
        sender.sendMessage(ChatColor.YELLOW + "/okotunpc relationship <npcId> <player> <delta|action:<key>>");
        sender.sendMessage(ChatColor.YELLOW + "/okotunpc state <npcId> <happiness|fear|anger|fatigue|hunger> <0-100>");
        sender.sendMessage(ChatColor.YELLOW + "/okotunpc enable <npcId>  (lets this NPC use AI chat - console-friendly)");
        sender.sendMessage(ChatColor.YELLOW + "/okotunpc disable <npcId>  (stops this NPC from using AI chat)");
        sender.sendMessage(ChatColor.YELLOW + "/okotunpc version  (shows the running version and AI parameters, never SQL/MySQL settings)");
        sender.sendMessage(ChatColor.YELLOW + "/okotunpc info <npcId> [player]");
    }

    // ---------------------------------------------------------------
    // profile
    // ---------------------------------------------------------------
    private void handleProfile(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "Usage: /okotunpc profile <npcId> <field> <value...>");
            return;
        }
        Integer npcId = parseInt(sender, args[1]);
        if (npcId == null) return;
        String field = args[2].toLowerCase();
        if (!PROFILE_FIELDS.contains(field)) {
            sender.sendMessage(ChatColor.RED + "Unknown field. Valid fields: " + String.join(", ", PROFILE_FIELDS));
            return;
        }
        String value = String.join(" ", Arrays.copyOfRange(args, 3, args.length));

        NpcProfileDao dao = plugin.getNpcProfileDao();
        runAsync(sender, "Error updating profile field", () -> {
            dao.findOrCreate(npcId, "NPC-" + npcId);
            dao.updateField(npcId, field, value);
            sender.sendMessage(ChatColor.GREEN + "NPC " + npcId + ": " + field + " updated.");
        });
    }

    // ---------------------------------------------------------------
    // knowledge
    // ---------------------------------------------------------------
    private void handleKnowledge(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "Usage: /okotunpc knowledge add|remove <npcId> <topic> [text...]");
            return;
        }
        String action = args[1].toLowerCase();
        Integer npcId = parseInt(sender, args[2]);
        if (npcId == null) return;
        String topic = args[3];
        KnowledgeDao dao = plugin.getKnowledgeDao();

        if ("add".equals(action)) {
            if (args.length < 5) {
                sender.sendMessage(ChatColor.RED + "Usage: /okotunpc knowledge add <npcId> <topic> <text...>");
                return;
            }
            String text = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
            runAsync(sender, "Error saving knowledge entry", () -> {
                dao.upsert(npcId, topic, text);
                sender.sendMessage(ChatColor.GREEN + "Knowledge '" + topic + "' saved for NPC " + npcId + ".");
            });
        } else if ("remove".equals(action)) {
            runAsync(sender, "Error removing knowledge entry", () -> {
                boolean removed = dao.remove(npcId, topic);
                sender.sendMessage(removed
                        ? ChatColor.GREEN + "Knowledge '" + topic + "' removed."
                        : ChatColor.YELLOW + "No such knowledge entry found.");
            });
        } else {
            sender.sendMessage(ChatColor.RED + "Unknown action, use 'add' or 'remove'.");
        }
    }

    // ---------------------------------------------------------------
    // event (village_events)
    // ---------------------------------------------------------------
    private void handleEvent(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /okotunpc event add|remove ...");
            return;
        }
        String action = args[1].toLowerCase();
        VillageEventDao dao = plugin.getVillageEventDao();

        if ("add".equals(action)) {
            if (args.length < 6) {
                sender.sendMessage(ChatColor.RED
                        + "Usage: /okotunpc event add <village> <priority> <expiresHours|never> <summary...>");
                return;
            }
            String village = args[2];
            Integer priority = parseInt(sender, args[3]);
            if (priority == null) return;
            Instant expires = null;
            if (!"never".equalsIgnoreCase(args[4])) {
                Integer hours = parseInt(sender, args[4]);
                if (hours == null) return;
                expires = Instant.now().plus(hours, ChronoUnit.HOURS);
            }
            String summary = String.join(" ", Arrays.copyOfRange(args, 5, args.length));
            Instant finalExpires = expires;
            runAsync(sender, "Error saving village event", () -> {
                long id = dao.add(village, priority, summary, finalExpires);
                sender.sendMessage(ChatColor.GREEN + "Event #" + id + " added for village '" + village + "'.");
            });
        } else if ("remove".equals(action)) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /okotunpc event remove <eventId>");
                return;
            }
            Long id;
            try {
                id = Long.parseLong(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid event id.");
                return;
            }
            runAsync(sender, "Error removing village event", () -> {
                boolean removed = dao.remove(id);
                sender.sendMessage(removed
                        ? ChatColor.GREEN + "Event #" + id + " removed."
                        : ChatColor.YELLOW + "No such event found.");
            });
        } else {
            sender.sendMessage(ChatColor.RED + "Unknown action, use 'add' or 'remove'.");
        }
    }

    // ---------------------------------------------------------------
    // relationship
    // ---------------------------------------------------------------
    private void handleRelationship(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "Usage: /okotunpc relationship <npcId> <player> <delta|action:<key>>");
            return;
        }
        Integer npcId = parseInt(sender, args[1]);
        if (npcId == null) return;
        UUID playerUuid = resolvePlayer(sender, args[2]);
        if (playerUuid == null) return;

        String deltaArg = args[3];
        PlayerMemoryDao dao = plugin.getPlayerMemoryDao();

        if (deltaArg.startsWith("action:")) {
            String actionKey = deltaArg.substring("action:".length());
            runAsync(sender, "Error applying relationship action", () -> {
                Integer configured = plugin.getPluginConfig().relationshipActionDelta(actionKey);
                if (configured == null) {
                    sender.sendMessage(ChatColor.RED + "Unknown action '" + actionKey
                            + "'. Check relationship.actions in config.yml.");
                    return;
                }
                int newScore = dao.adjustRelationship(npcId, playerUuid, configured,
                        plugin.getPluginConfig().relationshipMin, plugin.getPluginConfig().relationshipMax);
                sender.sendMessage(ChatColor.GREEN + "Applied '" + actionKey + "' (" + configured
                        + "). New score: " + newScore);
            });
        } else {
            Integer delta = parseInt(sender, deltaArg);
            if (delta == null) return;
            runAsync(sender, "Error adjusting relationship", () -> {
                int newScore = dao.adjustRelationship(npcId, playerUuid, delta,
                        plugin.getPluginConfig().relationshipMin, plugin.getPluginConfig().relationshipMax);
                sender.sendMessage(ChatColor.GREEN + "Relationship adjusted by " + delta
                        + ". New score: " + newScore);
            });
        }
    }

    // ---------------------------------------------------------------
    // state (npc_state)
    // ---------------------------------------------------------------
    private void handleState(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED
                    + "Usage: /okotunpc state <npcId> <happiness|fear|anger|fatigue|hunger> <0-100>");
            return;
        }
        Integer npcId = parseInt(sender, args[1]);
        if (npcId == null) return;
        String field = args[2].toLowerCase();
        Integer value = parseInt(sender, args[3]);
        if (value == null) return;

        NpcStateDao dao = plugin.getNpcStateDao();
        runAsync(sender, "Error updating NPC state", () -> {
            switch (field) {
                case "happiness" -> dao.update(npcId, value, null, null, null, null);
                case "fear" -> dao.update(npcId, null, value, null, null, null);
                case "anger" -> dao.update(npcId, null, null, value, null, null);
                case "fatigue" -> dao.update(npcId, null, null, null, value, null);
                case "hunger" -> dao.update(npcId, null, null, null, null, value);
                default -> {
                    sender.sendMessage(ChatColor.RED
                            + "Unknown field, use happiness|fear|anger|fatigue|hunger.");
                    return;
                }
            }
            sender.sendMessage(ChatColor.GREEN + "NPC " + npcId + ": " + field + " set to " + value + ".");
        });
    }

    // ---------------------------------------------------------------
    // enable / disable - gate whether an NPC can use AI chat at all.
    // Deliberately console-friendly: identifies the NPC by numeric id only,
    // no dependency on an in-game "selected NPC" (Citizens' own selection is
    // per-player and doesn't exist for a console sender).
    // ---------------------------------------------------------------
    private void handleEnable(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /okotunpc enable <npcId>");
            return;
        }
        Integer npcId = parseInt(sender, args[1]);
        if (npcId == null) return;

        String fallbackName = "NPC-" + npcId;
        runAsync(sender, "Error enabling AI for this NPC", () -> {
            plugin.getEnabledNpcRegistry().enable(npcId,
                    () -> plugin.getRandomProfileGenerator().generate(npcId, fallbackName));
            sender.sendMessage(ChatColor.GREEN + "NPC " + npcId + " can now use AI chat.");
        });
    }

    private void handleDisable(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /okotunpc disable <npcId>");
            return;
        }
        Integer npcId = parseInt(sender, args[1]);
        if (npcId == null) return;

        runAsync(sender, "Error disabling AI for this NPC", () -> {
            plugin.getEnabledNpcRegistry().disable(npcId);
            sender.sendMessage(ChatColor.YELLOW + "NPC " + npcId + " can no longer use AI chat.");
        });
    }

    // ---------------------------------------------------------------
    // version - running version + AI-related parameters only.
    // Deliberately never prints anything from the mysql-* config (host,
    // port, database, username, password, table-prefix): this command is
    // meant to be safe to run in front of other people / paste into a
    // support channel without leaking database access details.
    // No database access needed, so this runs synchronously - instant
    // output even from console.
    // ---------------------------------------------------------------
    private void handleVersion(CommandSender sender) {
        var cfg = plugin.getPluginConfig();

        sender.sendMessage(ChatColor.GOLD + "okotu-npc-ai-engine v" + plugin.getDescription().getVersion()
                + ChatColor.GRAY + "  by okotu71  (profile: " + cfg.activeProfile + ")");
        sender.sendMessage(ChatColor.GRAY + "GitHub: " + ChatColor.AQUA
                + "https://github.com/okotu71/NPC-AI-Engine");
        sender.sendMessage(ChatColor.GRAY + "Ollama docking: " + ChatColor.WHITE + cfg.ollamaBaseUrl);
        sender.sendMessage(ChatColor.GRAY + "Model: " + ChatColor.WHITE + cfg.ollamaDefaultModel
                + ChatColor.GRAY + "  | Summary model: " + ChatColor.WHITE + cfg.ollamaSummaryModel);
        sender.sendMessage(ChatColor.GRAY + "keep-alive=" + cfg.ollamaKeepAlive
                + " num-predict=" + cfg.ollamaNumPredict
                + " summary-num-predict=" + cfg.ollamaSummaryNumPredict
                + " temperature=" + cfg.ollamaTemperature);
        sender.sendMessage(ChatColor.GRAY + "num-ctx=" + cfg.ollamaNumCtx
                + " summary-num-ctx=" + cfg.ollamaSummaryNumCtx
                + " num-batch=" + cfg.ollamaNumBatch
                + " num-thread=" + (cfg.ollamaNumThread > 0 ? cfg.ollamaNumThread : "auto")
                + " num-gpu=" + (cfg.ollamaNumGpu > 0 ? cfg.ollamaNumGpu : "cpu-only"));
        sender.sendMessage(ChatColor.GRAY + "top-k=" + cfg.ollamaTopK
                + " top-p=" + cfg.ollamaTopP
                + " repeat-penalty=" + cfg.ollamaRepeatPenalty);
        sender.sendMessage(ChatColor.GRAY + "timeout-ms=" + cfg.ollamaTimeoutMs
                + " summary-timeout-ms=" + cfg.ollamaSummaryTimeoutMs
                + " max-retries=" + cfg.ollamaMaxRetries
                + " retry-delay-ms=" + cfg.ollamaRetryDelayMs
                + " debug-log=" + cfg.debugLogOllamaCommunication);
        sender.sendMessage(ChatColor.GRAY + "conversation: recent-messages=" + cfg.recentMessages
                + " summary-trigger=" + cfg.summaryTriggerMessages
                + " (" + cfg.summaryMaxWords + " words max)"
                + " cache=" + cfg.cacheMaxEntries + "/" + cfg.cacheExpireAfterMinutes + "min");
        sender.sendMessage(ChatColor.GRAY + "relationship: range " + cfg.relationshipMin + ".." + cfg.relationshipMax
                + " (default " + cfg.relationshipDefault + ")");
        sender.sendMessage(ChatColor.GRAY + "interaction: right-click=" + cfg.rightClickTriggerEnabled
                + " proximity=" + cfg.proximityTriggerEnabled
                + " (radius=" + cfg.proximityRadius + " interval=" + cfg.proximityCheckIntervalTicks + "t"
                + " cooldown=" + (cfg.proximityGreetCooldownMs / 60_000) + "m)"
                + " chat-capture-timeout=" + (cfg.chatCaptureTimeoutMs / 1000) + "s");
        sender.sendMessage(ChatColor.GRAY + "AI-enabled NPCs: " + ChatColor.WHITE
                + plugin.getEnabledNpcRegistry().enabledCount());
    }

    // ---------------------------------------------------------------
    // info
    // ---------------------------------------------------------------
    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /okotunpc info <npcId> [player]");
            return;
        }
        Integer npcId = parseInt(sender, args[1]);
        if (npcId == null) return;

        NpcProfileDao profileDao = plugin.getNpcProfileDao();
        NpcStateDao stateDao = plugin.getNpcStateDao();

        UUID playerUuid = null;
        String playerLabel = null;
        if (args.length >= 3) {
            playerUuid = resolvePlayer(sender, args[2]);
            if (playerUuid == null) return;
            playerLabel = args[2];
        }
        UUID finalPlayerUuid = playerUuid;
        String finalPlayerLabel = playerLabel;

        runAsync(sender, "Error reading NPC info", () -> {
            Optional<NpcProfile> profile = profileDao.find(npcId);
            if (profile.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "No profile stored for NPC " + npcId
                        + " - run /okotunpc enable " + npcId + " to create one and let it talk.");
                return;
            }
            NpcProfile p = profile.get();
            sender.sendMessage(ChatColor.GOLD + "NPC " + p.npcId() + " - " + p.name()
                    + (p.enabled() ? ChatColor.GREEN + " [AI enabled]" : ChatColor.RED + " [AI disabled]"));
            sender.sendMessage(ChatColor.DARK_GRAY + "okotu-npc-ai-engine v" + plugin.getDescription().getVersion()
                    + " by okotu71");
            sender.sendMessage(ChatColor.GRAY + "Role: " + p.role() + " | Profession: " + p.profession()
                    + " | Village: " + p.village());
            sender.sendMessage(ChatColor.GRAY + "Model (global, set in config.yml): "
                    + plugin.getPluginConfig().ollamaDefaultModel);
            sender.sendMessage(ChatColor.GRAY + "Personality: " + p.personality());
            sender.sendMessage(ChatColor.GRAY + "Background: " + p.background());

            NpcState state = stateDao.findOrCreate(npcId);
            sender.sendMessage(ChatColor.GRAY + String.format(
                    "State: happiness=%d fear=%d anger=%d fatigue=%d hunger=%d",
                    state.happiness(), state.fear(), state.anger(), state.fatigue(), state.hunger()));

            if (finalPlayerUuid != null) {
                Optional<PlayerMemory> memory = plugin.getPlayerMemoryDao().find(npcId, finalPlayerUuid);
                if (memory.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "No memory of player '" + finalPlayerLabel + "' yet.");
                } else {
                    PlayerMemory m = memory.get();
                    sender.sendMessage(ChatColor.AQUA + "Memory of " + finalPlayerLabel + ": relationship="
                            + m.relationshipScore() + " lastSeen=" + m.lastSeen()
                            + " messagesSinceSummary=" + m.messagesSinceSummary());
                    sender.sendMessage(ChatColor.AQUA + "Summary: "
                            + (m.summary() != null ? m.summary() : "(none yet)"));
                }
            }

            List<KnowledgeEntry> knowledge = plugin.getKnowledgeDao().findForNpc(npcId, 50);
            sender.sendMessage(ChatColor.GRAY + "Knowledge topics: "
                    + knowledge.stream().map(KnowledgeEntry::topic).reduce((a, b) -> a + ", " + b).orElse("(none)"));

            if (p.village() != null) {
                List<VillageEvent> events = plugin.getVillageEventDao().findActive(p.village(), 10);
                sender.sendMessage(ChatColor.GRAY + "Active village events: " + events.size());
                for (VillageEvent e : events) {
                    sender.sendMessage(ChatColor.DARK_GRAY + "  #" + e.id() + " (p" + e.priority() + "): "
                            + e.summary());
                }
            }
        });
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------
    private Integer parseInt(CommandSender sender, String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "'" + s + "' is not a valid number.");
            return null;
        }
    }

    /** Resolves a player name to a UUID. Runs on the calling thread (normally the main thread for commands). */
    private UUID resolvePlayer(CommandSender sender, String name) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.getUniqueId() == null) {
            sender.sendMessage(ChatColor.RED + "Could not resolve player '" + name + "'.");
            return null;
        }
        return offline.getUniqueId();
    }

    private void runAsync(CommandSender sender, String errorContext, ThrowingRunnable action) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    action.run();
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, errorContext, e);
                    sender.sendMessage(ChatColor.RED + errorContext + " (see console).");
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
