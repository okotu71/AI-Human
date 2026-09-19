package com.okotu.aihuman.command;

import com.okotu.aihuman.AiHumanPlugin;
import com.okotu.aihuman.db.KnowledgeDao;
import com.okotu.aihuman.db.NpcProfileDao;
import com.okotu.aihuman.db.NpcStateDao;
import com.okotu.aihuman.db.PlayerMemoryDao;
import com.okotu.aihuman.db.VillageEventDao;
import com.okotu.aihuman.model.KnowledgeEntry;
import com.okotu.aihuman.model.NpcBehaviorConfig;
import com.okotu.aihuman.model.NpcBehaviorType;
import com.okotu.aihuman.model.NpcProfile;
import com.okotu.aihuman.model.NpcState;
import com.okotu.aihuman.model.NpcWaypoint;
import com.okotu.aihuman.model.PlayerMemory;
import com.okotu.aihuman.model.VillageEvent;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public class AiHumanCommand implements CommandExecutor {

    private static final List<String> PROFILE_FIELDS = List.of(
            "name", "role", "personality", "background", "village",
            "profession", "speech_style", "knowledge", "system_prompt");

    private final AiHumanPlugin plugin;

    public AiHumanCommand(AiHumanPlugin plugin) {
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
                sender.sendMessage(ChatColor.GREEN + "AI-Human: configuration reloaded.");
            }
            case "profile" -> handleProfile(sender, args);
            case "knowledge" -> handleKnowledge(sender, args);
            case "event" -> handleEvent(sender, args);
            case "relationship" -> handleRelationship(sender, args);
            case "state" -> handleState(sender, args);
            case "enable" -> handleEnable(sender, args);
            case "disable" -> handleDisable(sender, args);
            case "autonomous" -> handleAutonomous(sender, args);
            case "wander" -> handleWander(sender, args);
            case "behavior" -> handleBehaviorType(sender, args);
            case "travel" -> handleTravel(sender, args);
            case "version" -> handleVersion(sender);
            case "info" -> handleInfo(sender, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "/aihuman reload");
        sender.sendMessage(ChatColor.YELLOW + "/aihuman profile <npcId> <field> <value...>  "
                + "(fields: " + String.join(", ", PROFILE_FIELDS) + ")");
        sender.sendMessage(ChatColor.YELLOW + "/aihuman knowledge add|remove <npcId> <topic> [text...]");
        sender.sendMessage(ChatColor.YELLOW + "/aihuman event add <village> <priority> <expiresHours|never> <summary...>");
        sender.sendMessage(ChatColor.YELLOW + "/aihuman event remove <eventId>");
        sender.sendMessage(ChatColor.YELLOW + "/aihuman relationship <npcId> <player> <delta|action:<key>>");
        sender.sendMessage(ChatColor.YELLOW + "/aihuman state <npcId> <happiness|fear|anger|fatigue|hunger> <0-100>");
        sender.sendMessage(ChatColor.YELLOW + "/aihuman enable <npcId>  (lets this NPC use AI chat - console-friendly)");
        sender.sendMessage(ChatColor.YELLOW + "/aihuman disable <npcId>  (stops this NPC from using AI chat)");
        sender.sendMessage(ChatColor.YELLOW + "/aihuman autonomous <npcId> on|off  (1.11+: autonomous wandering)");
        sender.sendMessage(ChatColor.YELLOW + "/aihuman wander <npcId> <radius> <minDistance> <maxDistance>");
        sender.sendMessage(ChatColor.YELLOW + "/aihuman behavior <npcId> <WANDER|VILLAGE|TRAVEL|GUARD|FOLLOW>");
        sender.sendMessage(ChatColor.YELLOW + "/aihuman travel <npcId> add [world] [x] [y] [z] | list | clear");
        sender.sendMessage(ChatColor.YELLOW + "/aihuman version  (shows the running version and AI parameters, never SQL/MySQL settings)");
        sender.sendMessage(ChatColor.YELLOW + "/aihuman info <npcId> [player]");
    }

    // ---------------------------------------------------------------
    // profile
    // ---------------------------------------------------------------
    private void handleProfile(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "Usage: /aihuman profile <npcId> <field> <value...>");
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
            sender.sendMessage(ChatColor.RED + "Usage: /aihuman knowledge add|remove <npcId> <topic> [text...]");
            return;
        }
        String action = args[1].toLowerCase();
        Integer npcId = parseInt(sender, args[2]);
        if (npcId == null) return;
        String topic = args[3];
        KnowledgeDao dao = plugin.getKnowledgeDao();

        if ("add".equals(action)) {
            if (args.length < 5) {
                sender.sendMessage(ChatColor.RED + "Usage: /aihuman knowledge add <npcId> <topic> <text...>");
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
            sender.sendMessage(ChatColor.RED + "Usage: /aihuman event add|remove ...");
            return;
        }
        String action = args[1].toLowerCase();
        VillageEventDao dao = plugin.getVillageEventDao();

        if ("add".equals(action)) {
            if (args.length < 6) {
                sender.sendMessage(ChatColor.RED
                        + "Usage: /aihuman event add <village> <priority> <expiresHours|never> <summary...>");
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
                sender.sendMessage(ChatColor.RED + "Usage: /aihuman event remove <eventId>");
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
            sender.sendMessage(ChatColor.RED + "Usage: /aihuman relationship <npcId> <player> <delta|action:<key>>");
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
                    + "Usage: /aihuman state <npcId> <happiness|fear|anger|fatigue|hunger> <0-100>");
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
            sender.sendMessage(ChatColor.RED + "Usage: /aihuman enable <npcId>");
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
            sender.sendMessage(ChatColor.RED + "Usage: /aihuman disable <npcId>");
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
    // autonomous / wander / behavior (1.11+) - autonomous movement.
    // Requires the NPC to already be AI-enabled (/aihuman enable) - this
    // is a layer on top of that, not a replacement for it.
    // ---------------------------------------------------------------
    private void handleAutonomous(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /aihuman autonomous <npcId> on|off");
            return;
        }
        Integer npcId = parseInt(sender, args[1]);
        if (npcId == null) return;
        String action = args[2].toLowerCase();

        if ("off".equals(action)) {
            runAsync(sender, "Error disabling autonomous movement", () -> {
                plugin.getAutonomousNpcRegistry().disable(npcId);
                sender.sendMessage(ChatColor.YELLOW + "NPC " + npcId + " is no longer autonomous "
                        + "(will stay where it is, still AI-enabled for conversation).");
            });
            return;
        }
        if (!"on".equals(action)) {
            sender.sendMessage(ChatColor.RED + "Usage: /aihuman autonomous <npcId> on|off");
            return;
        }

        if (!plugin.getEnabledNpcRegistry().isEnabled(npcId)) {
            sender.sendMessage(ChatColor.RED + "NPC " + npcId + " isn't AI-enabled yet - run "
                    + "/aihuman enable " + npcId + " first.");
            return;
        }

        // Must resolve the NPC's current location on the calling thread (main thread
        // for both in-game and console commands) before handing off to the async DB
        // write below - Citizens entity access isn't something to do off-thread.
        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
        if (npc == null || !npc.isSpawned()) {
            sender.sendMessage(ChatColor.RED + "NPC " + npcId + " doesn't exist or isn't spawned right now "
                    + "(it needs to be spawned once so its current position can be captured as its home point).");
            return;
        }
        Location home = npc.getEntity().getLocation();

        runAsync(sender, "Error enabling autonomous movement", () -> {
            plugin.getAutonomousNpcRegistry().enable(npcId, home);
            sender.sendMessage(ChatColor.GREEN + "NPC " + npcId + " is now autonomous - home set to "
                    + formatLocation(home) + ". Use /aihuman wander to tune its roam radius.");
        });
    }

    private void handleWander(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage(ChatColor.RED
                    + "Usage: /aihuman wander <npcId> <radius> <minDistance> <maxDistance>");
            return;
        }
        Integer npcId = parseInt(sender, args[1]);
        if (npcId == null) return;
        Integer radius = parseInt(sender, args[2]);
        Integer minDistance = parseInt(sender, args[3]);
        Integer maxDistance = parseInt(sender, args[4]);
        if (radius == null || minDistance == null || maxDistance == null) return;
        if (minDistance > maxDistance) {
            sender.sendMessage(ChatColor.RED + "minDistance can't be greater than maxDistance.");
            return;
        }
        if (minDistance > radius) {
            sender.sendMessage(ChatColor.RED + "minDistance (" + minDistance + ") is greater than radius ("
                    + radius + ") - every hop would be guaranteed to land outside the roam boundary and get "
                    + "rejected, so the NPC would never move at all. radius must be >= maxDistance. "
                    + "Example: for a wide 2000-block roam area with 200-500 block hops, use "
                    + "/aihuman wander " + npcId + " 2000 200 500 (radius first, then min, then max).");
            return;
        }
        if (maxDistance > radius) {
            sender.sendMessage(ChatColor.YELLOW + "Note: maxDistance (" + maxDistance + ") is greater than radius ("
                    + radius + ") - hops will be capped at " + radius + " blocks in practice, maxDistance won't "
                    + "be fully used. Set radius >= maxDistance if you want the full range to apply.");
        }
        if (!plugin.getAutonomousNpcRegistry().isAutonomous(npcId)) {
            sender.sendMessage(ChatColor.RED + "NPC " + npcId + " isn't autonomous yet - run "
                    + "/aihuman autonomous " + npcId + " on first.");
            return;
        }

        runAsync(sender, "Error updating wander settings", () -> {
            plugin.getAutonomousNpcRegistry().updateWander(npcId, radius, minDistance, maxDistance);
            sender.sendMessage(ChatColor.GREEN + "NPC " + npcId + " wander settings updated: radius=" + radius
                    + " minDistance=" + minDistance + " maxDistance=" + maxDistance);
        });
    }

    private void handleBehaviorType(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED
                    + "Usage: /aihuman behavior <npcId> <WANDER|VILLAGE|TRAVEL|GUARD|FOLLOW>");
            return;
        }
        Integer npcId = parseInt(sender, args[1]);
        if (npcId == null) return;
        NpcBehaviorType type;
        try {
            type = NpcBehaviorType.valueOf(args[2].toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(ChatColor.RED + "Unknown behavior type. Valid: WANDER, VILLAGE, TRAVEL, GUARD, FOLLOW.");
            return;
        }
        if (!plugin.getAutonomousNpcRegistry().isAutonomous(npcId)) {
            sender.sendMessage(ChatColor.RED + "NPC " + npcId + " isn't autonomous yet - run "
                    + "/aihuman autonomous " + npcId + " on first.");
            return;
        }
        if (type != NpcBehaviorType.WANDER && type != NpcBehaviorType.TRAVEL) {
            sender.sendMessage(ChatColor.YELLOW + "Note: only WANDER and TRAVEL are actually implemented in "
                    + "this version - " + type + " will be saved but behave like WANDER for now.");
        } else if (type == NpcBehaviorType.TRAVEL) {
            sender.sendMessage(ChatColor.YELLOW + "Reminder: TRAVEL needs waypoints to do anything - see "
                    + "/aihuman travel " + npcId + " add. With none set, this NPC will just stand still.");
        }

        runAsync(sender, "Error updating behavior type", () -> {
            plugin.getAutonomousNpcRegistry().updateBehaviorType(npcId, type);
            sender.sendMessage(ChatColor.GREEN + "NPC " + npcId + " behavior type set to " + type + ".");
        });
    }

    // ---------------------------------------------------------------
    // travel (1.17+) - waypoints for TRAVEL-behavior NPCs.
    // ---------------------------------------------------------------
    private void handleTravel(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /aihuman travel <npcId> add [world] [x] [y] [z] | list | clear");
            return;
        }
        Integer npcId = parseInt(sender, args[1]);
        if (npcId == null) return;
        String action = args[2].toLowerCase();

        switch (action) {
            case "add" -> handleTravelAdd(sender, args, npcId);
            case "list" -> handleTravelList(sender, npcId);
            case "clear" -> runAsync(sender, "Error clearing waypoints", () -> {
                plugin.getNpcWaypointRegistry().clear(npcId);
                sender.sendMessage(ChatColor.GREEN + "Cleared all waypoints for NPC " + npcId + ".");
            });
            default -> sender.sendMessage(ChatColor.RED
                    + "Usage: /aihuman travel <npcId> add [world] [x] [y] [z] | list | clear");
        }
    }

    private void handleTravelAdd(CommandSender sender, String[] args, int npcId) {
        Location location;
        if (args.length >= 7) {
            // Explicit coordinates - works from console too.
            World world = Bukkit.getWorld(args[3]);
            if (world == null) {
                sender.sendMessage(ChatColor.RED + "Unknown world '" + args[3] + "'.");
                return;
            }
            Double x = parseDouble(sender, args[4]);
            Double y = parseDouble(sender, args[5]);
            Double z = parseDouble(sender, args[6]);
            if (x == null || y == null || z == null) return;
            location = new Location(world, x, y, z);
        } else if (sender instanceof Player player) {
            // No coordinates given - use the sender's own current position.
            location = player.getLocation();
        } else {
            sender.sendMessage(ChatColor.RED
                    + "Console needs explicit coordinates: /aihuman travel " + npcId + " add <world> <x> <y> <z>");
            return;
        }

        Location finalLocation = location;
        runAsync(sender, "Error adding waypoint", () -> {
            plugin.getNpcWaypointRegistry().add(npcId, finalLocation);
            sender.sendMessage(ChatColor.GREEN + "Waypoint added for NPC " + npcId + " at "
                    + formatLocation(finalLocation) + ".");
        });
    }

    private void handleTravelList(CommandSender sender, int npcId) {
        List<NpcWaypoint> waypoints = plugin.getNpcWaypointRegistry().get(npcId);
        if (waypoints.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "NPC " + npcId + " has no waypoints yet - "
                    + "/aihuman travel " + npcId + " add");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "Waypoints for NPC " + npcId + " (" + waypoints.size()
                + ", visited in order, looping back to the first):");
        for (NpcWaypoint wp : waypoints) {
            sender.sendMessage(ChatColor.GRAY + "  #" + wp.sequence() + ": " + wp.world()
                    + String.format(" (%.0f, %.0f, %.0f)", wp.x(), wp.y(), wp.z()));
        }
    }

    private Double parseDouble(CommandSender sender, String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "'" + s + "' is not a valid number.");
            return null;
        }
    }

    private String formatLocation(Location loc) {
        return String.format("%s (%.0f, %.0f, %.0f)",
                loc.getWorld() != null ? loc.getWorld().getName() : "?", loc.getX(), loc.getY(), loc.getZ());
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

        sender.sendMessage(ChatColor.GOLD + "AI-Human v" + plugin.getDescription().getVersion()
                + ChatColor.GRAY + "  by okotu71  (profile: " + cfg.activeProfile + ")");
        sender.sendMessage(ChatColor.GRAY + "GitHub: " + ChatColor.AQUA
                + "https://github.com/okotu71/AI-Human");
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
        sender.sendMessage(ChatColor.GRAY + "autonomous: enabled=" + cfg.autonomousEnabled
                + " interval=" + cfg.autonomousCheckIntervalTicks + "t"
                + " default-radius=" + cfg.autonomousDefaultWanderRadius
                + " (" + cfg.autonomousDefaultWanderMinDistance + "-" + cfg.autonomousDefaultWanderMaxDistance + ")"
                + " detection=" + cfg.autonomousDefaultDetectionRadius);
        sender.sendMessage(ChatColor.GRAY + "world-bubble=" + cfg.worldBubbleEnabled
                + " pl3xmap=" + cfg.pl3xMapEnabled
                + (cfg.pl3xMapEnabled ? " (present=" + plugin.getPl3xMapIntegration().isPresent() + ")" : ""));
        sender.sendMessage(ChatColor.GRAY + "AI-enabled NPCs: " + ChatColor.WHITE
                + plugin.getEnabledNpcRegistry().enabledCount()
                + ChatColor.GRAY + "  | Autonomous NPCs: " + ChatColor.WHITE
                + plugin.getAutonomousNpcRegistry().autonomousCount());
    }

    // ---------------------------------------------------------------
    // info
    // ---------------------------------------------------------------
    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /aihuman info <npcId> [player]");
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
                        + " - run /aihuman enable " + npcId + " to create one and let it talk.");
                return;
            }
            NpcProfile p = profile.get();
            sender.sendMessage(ChatColor.GOLD + "NPC " + p.npcId() + " - " + p.name()
                    + (p.enabled() ? ChatColor.GREEN + " [AI enabled]" : ChatColor.RED + " [AI disabled]"));
            sender.sendMessage(ChatColor.DARK_GRAY + "AI-Human v" + plugin.getDescription().getVersion()
                    + " by okotu71");
            Optional<NpcBehaviorConfig> behavior = plugin.getAutonomousNpcRegistry().get(p.npcId());
            if (behavior.isPresent()) {
                NpcBehaviorConfig b = behavior.get();
                sender.sendMessage(ChatColor.AQUA + "Autonomous: " + b.behaviorType()
                        + (b.hasHome() ? " | home: " + formatLocation(
                                new Location(Bukkit.getWorld(b.homeWorld()), b.homeX(), b.homeY(), b.homeZ()))
                                : ""));
            } else {
                sender.sendMessage(ChatColor.DARK_GRAY + "Autonomous: off (stationary)");
            }
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
