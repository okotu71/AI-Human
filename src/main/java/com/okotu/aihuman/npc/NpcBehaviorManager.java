package com.okotu.aihuman.npc;

import com.okotu.aihuman.AiHumanPlugin;
import com.okotu.aihuman.config.PluginConfig;
import com.okotu.aihuman.model.NpcBehaviorConfig;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

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
    private final NpcMovementController movementController = new NpcMovementController();

    public NpcBehaviorManager(AiHumanPlugin plugin) {
        this.plugin = plugin;
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
    // toward a randomly-chosen, safety-checked destination.
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

        if (!npc.getNavigator().isNavigating()) {
            Location home = resolveHome(behaviorConfig, npcLocation);
            movementController.pickDestination(home, settings)
                    .ifPresent(destination -> movementController.navigateTo(npc, destination, settings.walkSpeed()));
            // If no safe destination was found this cycle, just do nothing - the NPC
            // stays put and tries again next check-interval-ticks.
        }
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
