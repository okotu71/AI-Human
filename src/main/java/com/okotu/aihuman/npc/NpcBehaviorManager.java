package com.okotu.aihuman.npc;

import com.okotu.aihuman.AiHumanPlugin;
import com.okotu.aihuman.config.PluginConfig;
import com.okotu.aihuman.model.NpcBehaviorConfig;
import com.okotu.aihuman.model.NpcBehaviorType;
import com.okotu.aihuman.model.NpcWaypoint;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Runs on the main thread every {@code interaction.autonomous.check-interval-ticks}
 * and drives the WANDERING -&gt; APPROACHING -&gt; TALKING -&gt; WANDERING state
 * machine (see {@link NpcActivityState}) for every NPC that is both
 * AI-enabled ({@code EnabledNpcRegistry}) and autonomous
 * ({@code AutonomousNpcRegistry}).
 *
 * <p>Deliberately separate from {@code ProximityGreetingTask}, which keeps
 * handling stationary AI NPCs (autonomous=false) exactly as it did in 1.10 -
 * this class only ever touches NPCs that have explicitly opted into
 * autonomous movement, so enabling this subsystem can't change how any
 * existing NPC behaves.
 *
 * <p>The model never decides individual steps: WANDERING asks
 * {@code NpcMovementController} for one destination per wander cycle and
 * hands it to Citizens' Navigator, which does the actual pathfinding/walking.
 * The only place the LLM gets involved is generating what the NPC actually
 * says once it reaches TALKING - via the same
 * {@code ConversationService#greetApproachingPlayer} already used by
 * {@code ProximityGreetingTask}.
 */
public class NpcBehaviorManager implements Runnable {

    private final AiHumanPlugin plugin;
    private final Map<Integer, NpcController> controllers = new ConcurrentHashMap<>();
    private final NpcMovementController movementController;

    public NpcBehaviorManager(AiHumanPlugin plugin) {
        this.plugin = plugin;
        this.movementController = new NpcMovementController(plugin);
    }

    private boolean debugMovement() {
        return plugin.getPluginConfig().debugLogMovement;
    }

    private void debug(String message) {
        if (debugMovement()) {
            plugin.getLogger().log(Level.INFO, "[AI-Human MOVE] " + message);
        }
    }

    @Override
    public void run() {
        PluginConfig config = plugin.getPluginConfig();
        if (!config.autonomousEnabled) {
            return;
        }

        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.isSpawned()) {
                continue;
            }
            if (!plugin.getEnabledNpcRegistry().isEnabled(npc.getId())) {
                continue; // must be AI-enabled at all, same gate as every other trigger
            }
            Optional<NpcBehaviorConfig> behaviorConfig = plugin.getAutonomousNpcRegistry().get(npc.getId());
            if (behaviorConfig.isEmpty()) {
                continue; // AI-enabled but not autonomous - ProximityGreetingTask handles this one
            }

            NpcController controller = controllers.computeIfAbsent(npc.getId(), NpcController::new);
            NpcBehaviorSettings settings = NpcBehaviorSettings.resolve(config, behaviorConfig.get());

            if (debugMovement()) {
                debug("tick: NPC " + npc.getId() + " (" + npc.getName() + ") state=" + controller.state()
                        + " behaviorType=" + behaviorConfig.get().behaviorType()
                        + " navigating=" + (npc.isSpawned() && npc.getNavigator().isNavigating()));
            }

            try {
                switch (controller.state()) {
                    case WANDERING -> updateWandering(npc, behaviorConfig.get(), controller, settings);
                    case APPROACHING -> updateApproaching(npc, controller, settings);
                    case TALKING -> updateTalking(npc, controller);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error updating autonomous behavior for NPC " + npc.getId(), e);
            }
        }
    }

    // ---------------------------------------------------------------
    // WANDERING: look for a player to approach; otherwise keep walking
    // toward the next destination - a random safety-checked point for
    // WANDER behavior, or the next waypoint in sequence for TRAVEL.
    // ---------------------------------------------------------------
    private void updateWandering(NPC npc, NpcBehaviorConfig behaviorConfig, NpcController controller,
                                  NpcBehaviorSettings settings) {
        Location npcLocation = npc.getEntity().getLocation();

        Optional<Player> nearby = NpcPerception.findNearestPlayer(npcLocation, settings.detectionRadius(),
                player -> player.hasPermission("aihuman.talk")
                        && !plugin.getConversationSessionManager().isActive(player.getUniqueId()));

        if (nearby.isPresent()) {
            Player player = nearby.get();
            controller.setTargetPlayerUuid(player.getUniqueId());
            controller.setState(NpcActivityState.APPROACHING);
            Location approachPoint = computeApproachPoint(npcLocation, player.getLocation(),
                    settings.approachStopDistance());
            movementController.navigateTo(npc, approachPoint, settings.walkSpeed());
            return;
        }

        if (npc.getNavigator().isNavigating()) {
            if (hasMadeProgress(controller, npcLocation, settings)) {
                if (debugMovement()) {
                    debug("NPC " + npc.getId() + ": still navigating, made progress (now at "
                            + npcLocation.getBlockX() + "," + npcLocation.getBlockY() + "," + npcLocation.getBlockZ()
                            + ") - resetting stuck timer");
                }
                controller.setLastProgressPosition(npcLocation);
                controller.setLastProgressAtMillis(System.currentTimeMillis());
                return; // actively making progress toward its current destination - nothing to do
            }
            long stuckForMs = System.currentTimeMillis() - controller.lastProgressAtMillis();
            if (stuckForMs < settings.stuckTimeoutMs()) {
                if (debugMovement()) {
                    debug("NPC " + npc.getId() + ": navigating, no progress for " + stuckForMs
                            + "ms (timeout " + settings.stuckTimeoutMs() + "ms) - waiting");
                }
                return; // navigating, no progress yet, but not stuck long enough to give up on it
            }
            // Stuck: Citizens' own Navigator still reports "navigating" but the NPC
            // hasn't actually moved in stuckTimeoutMs - give up on this path and
            // fall through below to immediately pick a fresh destination.
            if (debugMovement()) {
                debug("NPC " + npc.getId() + ": stuck for " + stuckForMs + "ms >= timeout "
                        + settings.stuckTimeoutMs() + "ms - cancelling navigation and picking a new destination");
            }
            npc.getNavigator().cancelNavigation();
        }

        Optional<Location> next = pickNextDestination(npc, behaviorConfig, controller, settings, npcLocation);
        if (next.isPresent()) {
            movementController.navigateTo(npc, next.get(), settings.walkSpeed());
            controller.setLastProgressPosition(npcLocation);
            controller.setLastProgressAtMillis(System.currentTimeMillis());
        } else if (debugMovement()) {
            debug("NPC " + npc.getId() + ": no destination available this cycle ("
                    + behaviorConfig.behaviorType() + ") - staying put until next check-interval-ticks");
        }
        // If no destination was available this cycle (WANDER found nothing safe, or
        // TRAVEL has no waypoints configured yet), the NPC just stays put and this
        // is retried next check-interval-ticks.
    }

    private Optional<Location> pickNextDestination(NPC npc, NpcBehaviorConfig behaviorConfig,
                                                     NpcController controller, NpcBehaviorSettings settings,
                                                     Location npcLocation) {
        if (behaviorConfig.behaviorType() == NpcBehaviorType.TRAVEL) {
            return nextWaypoint(npc, controller);
        }
        Location home = resolveHome(behaviorConfig, npcLocation);
        return movementController.pickDestination(home, settings);
    }

    /** Advances the NPC to the next waypoint in sequence, looping back to the first after the last. */
    private Optional<Location> nextWaypoint(NPC npc, NpcController controller) {
        List<NpcWaypoint> waypoints = plugin.getNpcWaypointRegistry().get(npc.getId());
        if (waypoints.isEmpty()) {
            if (debugMovement()) {
                debug("NPC " + npc.getId() + ": TRAVEL behavior but 0 waypoints loaded in NpcWaypointRegistry - "
                        + "run /aihuman travel " + npc.getId() + " add, or /aihuman travel " + npc.getId()
                        + " list to check what's actually stored");
            }
            return Optional.empty(); // TRAVEL selected but no waypoints yet - see /aihuman travel add
        }
        int index = controller.waypointIndex() % waypoints.size();
        NpcWaypoint waypoint = waypoints.get(index);
        World world = Bukkit.getWorld(waypoint.world());
        if (world == null) {
            if (debugMovement()) {
                debug("NPC " + npc.getId() + ": TRAVEL waypoint #" + waypoint.sequence() + " points to world '"
                        + waypoint.world() + "' which Bukkit.getWorld() can't find right now (not loaded, or "
                        + "the name doesn't match any world) - skipping this cycle");
            }
            return Optional.empty(); // that waypoint's world isn't loaded right now
        }
        controller.setWaypointIndex((index + 1) % waypoints.size());
        Location destination = new Location(world, waypoint.x(), waypoint.y(), waypoint.z());
        if (debugMovement()) {
            debug("NPC " + npc.getId() + ": TRAVEL heading to waypoint #" + waypoint.sequence() + "/"
                    + waypoints.size() + " at " + world.getName() + "@(" + waypoint.x() + "," + waypoint.y() + ","
                    + waypoint.z() + ")");
        }
        return Optional.of(destination);
    }

    /**
     * True if the NPC has moved at least {@code stuckMinProgressDistance}
     * since the last time progress was recorded (or if there's no baseline
     * yet / the world changed, in which case the clock just (re)starts).
     */
    private boolean hasMadeProgress(NpcController controller, Location current, NpcBehaviorSettings settings) {
        Location last = controller.lastProgressPosition();
        if (last == null || last.getWorld() == null || !last.getWorld().equals(current.getWorld())) {
            return true;
        }
        double minDistance = settings.stuckMinProgressDistance();
        return last.distanceSquared(current) >= minDistance * minDistance;
    }

    // ---------------------------------------------------------------
    // APPROACHING: walk toward the target player; once close enough (or the
    // player left range / logged off), resolve into TALKING or back to WANDERING.
    // ---------------------------------------------------------------
    private void updateApproaching(NPC npc, NpcController controller, NpcBehaviorSettings settings) {
        UUID targetUuid = controller.targetPlayerUuid();
        Player target = targetUuid != null ? Bukkit.getPlayer(targetUuid) : null;

        if (target == null || !target.isOnline()) {
            giveUpApproach(npc, controller);
            return;
        }

        Location npcLocation = npc.getEntity().getLocation();
        double distanceSquared = npcLocation.distanceSquared(target.getLocation());

        // Generous slack versus the detection radius itself, so a player drifting
        // slightly doesn't instantly cancel an approach that's already under way.
        double giveUpRangeSquared = Math.pow(settings.detectionRadius() * 2, 2);
        if (distanceSquared > giveUpRangeSquared) {
            giveUpApproach(npc, controller);
            return;
        }

        boolean closeEnough = distanceSquared <= settings.approachStopDistance() * settings.approachStopDistance();
        if (closeEnough || !npc.getNavigator().isNavigating()) {
            npc.getNavigator().cancelNavigation();
            npc.faceLocation(target.getLocation());
            controller.setState(NpcActivityState.TALKING);
            beginTalking(npc, target);
        }
    }

    private void giveUpApproach(NPC npc, NpcController controller) {
        npc.getNavigator().cancelNavigation();
        controller.setTargetPlayerUuid(null);
        controller.setState(NpcActivityState.WANDERING);
    }

    // ---------------------------------------------------------------
    // TALKING: opens the same conversation session/greeting flow as
    // ProximityGreetingTask, then just waits for the session to close
    // (NpcBridgeListener's chat capture handles the actual back-and-forth).
    // ---------------------------------------------------------------
    private void beginTalking(NPC npc, Player player) {
        int npcId = npc.getId();
        String npcName = npc.getName();

        plugin.getConversationSessionManager().start(player.getUniqueId(), npcId, npcName);

        plugin.getConversationService()
                .greetApproachingPlayer(npcId, npcName, player.getName(), player.getUniqueId())
                .whenComplete((greeting, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    // Same fix as ProximityGreetingTask (1.09): restart the chat-capture
                    // window now that the player has something to react to, guarded so a
                    // reply the player already sent while this was in flight isn't clobbered.
                    if (plugin.getConversationSessionManager().isActiveWith(player.getUniqueId(), npcId)) {
                        plugin.getConversationSessionManager().start(player.getUniqueId(), npcId, npcName);
                    }
                    if (throwable != null) {
                        plugin.getLogger().log(Level.FINE,
                                "Autonomous NPC greeting failed for NPC " + npcId + " (skipping silently)", throwable);
                        return;
                    }
                    player.sendMessage(ChatColor.GOLD + "[" + npcName + ChatColor.GOLD + "] "
                            + ChatColor.WHITE + greeting);
                    plugin.getNpcDialogRenderer().show(npc, greeting);
                }));
    }

    private void updateTalking(NPC npc, NpcController controller) {
        UUID targetUuid = controller.targetPlayerUuid();
        boolean stillTalking = targetUuid != null
                && plugin.getConversationSessionManager().isActiveWith(targetUuid, npc.getId());
        if (!stillTalking) {
            controller.setTargetPlayerUuid(null);
            controller.setState(NpcActivityState.WANDERING);
        }
        // While TALKING, movement stays put on purpose - NpcBridgeListener owns the
        // actual conversation via the session opened above.
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------
    private Location resolveHome(NpcBehaviorConfig behaviorConfig, Location fallback) {
        if (behaviorConfig.hasHome()) {
            World world = Bukkit.getWorld(behaviorConfig.homeWorld());
            if (world != null) {
                return new Location(world, behaviorConfig.homeX(), behaviorConfig.homeY(), behaviorConfig.homeZ());
            }
        }
        return fallback;
    }

    private Location computeApproachPoint(Location from, Location to, double stopDistance) {
        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();
        if (distance <= stopDistance || distance == 0) {
            return to.clone();
        }
        direction.normalize().multiply(distance - stopDistance);
        Location point = from.clone().add(direction);
        point.setY(to.getY());
        return point;
    }
}
