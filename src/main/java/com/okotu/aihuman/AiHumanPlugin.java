package com.okotu.aihuman;

import com.okotu.aihuman.ai.OllamaClient;
import com.okotu.aihuman.ai.PromptBuilder;
import com.okotu.aihuman.api.AiHumanApi;
import com.okotu.aihuman.api.AiHumanApiImpl;
import com.okotu.aihuman.cache.RecentMessageCache;
import com.okotu.aihuman.command.AiHumanCommand;
import com.okotu.aihuman.config.PluginConfig;
import com.okotu.aihuman.db.CleanupTask;
import com.okotu.aihuman.db.Database;
import com.okotu.aihuman.db.DialogHistoryDao;
import com.okotu.aihuman.db.KnowledgeDao;
import com.okotu.aihuman.db.NpcBehaviorDao;
import com.okotu.aihuman.db.NpcProfileDao;
import com.okotu.aihuman.db.NpcStateDao;
import com.okotu.aihuman.db.NpcWaypointDao;
import com.okotu.aihuman.db.PlayerMemoryDao;
import com.okotu.aihuman.db.VillageEventDao;
import com.okotu.aihuman.dialog.NpcDialogRenderer;
import com.okotu.aihuman.map.NpcMapStyle;
import com.okotu.aihuman.map.NpcMapSync;
import com.okotu.aihuman.map.Pl3xMapIntegration;
import com.okotu.aihuman.npc.AutonomousNpcRegistry;
import com.okotu.aihuman.npc.ConversationSessionManager;
import com.okotu.aihuman.npc.EnabledNpcRegistry;
import com.okotu.aihuman.npc.NpcBehaviorManager;
import com.okotu.aihuman.npc.NpcBridgeListener;
import com.okotu.aihuman.npc.NpcWaypointRegistry;
import com.okotu.aihuman.npc.ProximityGreetingTask;
import com.okotu.aihuman.service.ConversationService;
import com.okotu.aihuman.service.RandomProfileGenerator;
import com.okotu.aihuman.service.RelationshipService;
import com.okotu.aihuman.service.SummaryService;
import com.okotu.aihuman.util.RateLimiter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class AiHumanPlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private Database database;

    private NpcProfileDao npcProfileDao;
    private PlayerMemoryDao playerMemoryDao;
    private DialogHistoryDao dialogHistoryDao;
    private VillageEventDao villageEventDao;
    private KnowledgeDao knowledgeDao;
    private NpcStateDao npcStateDao;
    private NpcBehaviorDao npcBehaviorDao;
    private NpcWaypointDao npcWaypointDao;

    private RecentMessageCache recentMessageCache;
    private OllamaClient ollamaClient;
    private RelationshipService relationshipService;
    private SummaryService summaryService;
    private RandomProfileGenerator randomProfileGenerator;
    private ConversationService conversationService;
    private ConversationSessionManager conversationSessionManager;
    private EnabledNpcRegistry enabledNpcRegistry;
    private AutonomousNpcRegistry autonomousNpcRegistry;
    private NpcWaypointRegistry npcWaypointRegistry;
    private NpcDialogRenderer npcDialogRenderer;
    private Pl3xMapIntegration pl3xMapIntegration;
    private NpcMapStyle npcMapStyle;
    private NpcMapSync npcMapSync;

    private ExecutorService asyncExecutor;
    private AiHumanApiImpl apiImpl;

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
            getLogger().severe("Citizens not found: AI-Human requires the Citizens plugin. Disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        ensureDefaultConfigExists();

        this.asyncExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "okotu-npc-ai-worker");
            t.setDaemon(true);
            return t;
        });

        initializeComponents();
        registerApi();

        try {
            enabledNpcRegistry.loadInitialState();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE,
                    "Could not load the list of AI-enabled NPCs - starting with none enabled. "
                            + "Re-run /aihuman enable <npcId> for any NPC that should be talking, "
                            + "or fix the underlying database issue and restart.", e);
        }

        try {
            autonomousNpcRegistry.loadInitialState();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE,
                    "Could not load the list of autonomous NPCs - starting with none autonomous. "
                            + "Re-run /aihuman autonomous <npcId> on for any NPC that should be wandering, "
                            + "or fix the underlying database issue and restart.", e);
        }

        try {
            npcWaypointRegistry.loadInitialState();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE,
                    "Could not load TRAVEL waypoints - TRAVEL-behavior NPCs will stay put until this is fixed "
                            + "and the server restarted (or /aihuman travel add is used again).", e);
        }

        if (pluginConfig.pl3xMapEnabled && Bukkit.getPluginManager().getPlugin("Pl3xMap") == null) {
            getLogger().warning("integrations.pl3xmap.enabled is true, but no plugin named 'Pl3xMap' is "
                    + "installed on this server - NPC map markers will not appear until it is.");
        }

        getServer().getPluginManager().registerEvents(
                new NpcBridgeListener(this, new RateLimiter(pluginConfig.perPlayerCooldownMs)),
                this);

        var command = getCommand("aihuman");
        if (command != null) {
            command.setExecutor(new AiHumanCommand(this));
        }

        long intervalTicks = pluginConfig.cleanupIntervalMinutes * 60L * 20L; // minutes -> ticks (20 ticks/s)
        Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                new CleanupTask(this, dialogHistoryDao, villageEventDao, pluginConfig.maxRawMessagesSafety),
                intervalTicks, intervalTicks);

        // Runs on the MAIN thread (unlike the cleanup task above): it reads NPC/player
        // entity locations, which Citizens/Bukkit expect to be touched from the main thread.
        Bukkit.getScheduler().runTaskTimer(this, new ProximityGreetingTask(this),
                pluginConfig.proximityCheckIntervalTicks, pluginConfig.proximityCheckIntervalTicks);

        // Also main-thread: drives Navigator/world checks for autonomous NPCs only -
        // stationary AI NPCs keep going through ProximityGreetingTask above, unaffected.
        Bukkit.getScheduler().runTaskTimer(this, new NpcBehaviorManager(this),
                pluginConfig.autonomousCheckIntervalTicks, pluginConfig.autonomousCheckIntervalTicks);

        // Also main-thread (reads NPC entity locations); no-ops on its own if Pl3xMap
        // isn't installed or integrations.pl3xmap.enabled/npc-map.enabled is false.
        Bukkit.getScheduler().runTaskTimer(this, npcMapSync,
                pluginConfig.npcMapRefreshIntervalTicks, pluginConfig.npcMapRefreshIntervalTicks);

        getLogger().info("AI-Human v" + getDescription().getVersion() + " started."
                + " Profile: " + pluginConfig.activeProfile
                + " | Database: " + database.databaseName()
                + " | Table prefix: '" + pluginConfig.mysqlTablePrefix + "'"
                + " | Ollama docking: " + pluginConfig.ollamaBaseUrl
                + " | Default model: " + pluginConfig.ollamaDefaultModel
                + " | Right-click trigger: " + pluginConfig.rightClickTriggerEnabled
                + " | Proximity trigger: " + pluginConfig.proximityTriggerEnabled
                + " | Autonomous movement: " + pluginConfig.autonomousEnabled
                + " | AI-enabled NPCs: " + enabledNpcRegistry.enabledCount()
                + " | Autonomous NPCs: " + autonomousNpcRegistry.autonomousCount());
    }

    /**
     * Makes sure plugins/AI-Human/config.yml exists, creating it from
     * the bundled default on first run. See README "About plugin.yml" for
     * why plugin.yml itself can't be created this way.
     */
    private void ensureDefaultConfigExists() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            getLogger().info("config.yml not found, creating default configuration...");
            saveDefaultConfig();
        } else {
            reloadConfig();
        }
    }

    private void initializeComponents() {
        this.pluginConfig = new PluginConfig(this);
        this.database = new Database(this, pluginConfig);
        this.database.applySchema();

        this.npcProfileDao = new NpcProfileDao(database);
        this.playerMemoryDao = new PlayerMemoryDao(database, pluginConfig.relationshipDefault);
        this.dialogHistoryDao = new DialogHistoryDao(database);
        this.villageEventDao = new VillageEventDao(database);
        this.knowledgeDao = new KnowledgeDao(database);
        this.npcStateDao = new NpcStateDao(database);
        this.npcBehaviorDao = new NpcBehaviorDao(database);
        this.npcWaypointDao = new NpcWaypointDao(database);

        this.recentMessageCache = new RecentMessageCache(dialogHistoryDao, pluginConfig);
        this.ollamaClient = new OllamaClient(pluginConfig, getLogger(), asyncExecutor);
        this.relationshipService = new RelationshipService(playerMemoryDao, pluginConfig);
        this.summaryService = new SummaryService(pluginConfig, playerMemoryDao, dialogHistoryDao,
                recentMessageCache, ollamaClient, asyncExecutor, getLogger());
        this.randomProfileGenerator = new RandomProfileGenerator(pluginConfig);

        PromptBuilder promptBuilder = new PromptBuilder(relationshipService);
        this.conversationService = new ConversationService(pluginConfig, npcProfileDao, playerMemoryDao,
                knowledgeDao, villageEventDao, npcStateDao, recentMessageCache, ollamaClient, promptBuilder,
                summaryService, randomProfileGenerator, asyncExecutor, getLogger());

        this.conversationSessionManager = new ConversationSessionManager(pluginConfig.chatCaptureTimeoutMs);
        this.enabledNpcRegistry = new EnabledNpcRegistry(npcProfileDao);
        this.autonomousNpcRegistry = new AutonomousNpcRegistry(npcBehaviorDao);
        this.npcWaypointRegistry = new NpcWaypointRegistry(npcWaypointDao);
        this.npcDialogRenderer = new NpcDialogRenderer(this, pluginConfig);
        this.pl3xMapIntegration = new Pl3xMapIntegration(getLogger(), pluginConfig.npcMapDebug, "[AI-Human]");
        this.npcMapStyle = new NpcMapStyle(this);
        this.npcMapSync = new NpcMapSync(this, pl3xMapIntegration, npcMapStyle);
    }

    private void registerApi() {
        this.apiImpl = new AiHumanApiImpl(relationshipService, villageEventDao, knowledgeDao, npcStateDao, asyncExecutor);
        getServer().getServicesManager().register(AiHumanApi.class, apiImpl, this, ServicePriority.Normal);
    }

    /**
     * Called by /aihuman reload. Re-reads config.yml and re-creates the
     * dependent components. Does NOT hot-swap the MySQL pool (host/db/profile
     * changes still require a full server restart).
     */
    public void reloadPlugin() {
        reloadConfig();
        this.pluginConfig = new PluginConfig(this);
        this.ollamaClient = new OllamaClient(pluginConfig, getLogger(), asyncExecutor);
        this.relationshipService = new RelationshipService(playerMemoryDao, pluginConfig);
        this.summaryService = new SummaryService(pluginConfig, playerMemoryDao, dialogHistoryDao,
                recentMessageCache, ollamaClient, asyncExecutor, getLogger());
        this.randomProfileGenerator = new RandomProfileGenerator(pluginConfig);
        PromptBuilder promptBuilder = new PromptBuilder(relationshipService);
        this.conversationService = new ConversationService(pluginConfig, npcProfileDao, playerMemoryDao,
                knowledgeDao, villageEventDao, npcStateDao, recentMessageCache, ollamaClient, promptBuilder,
                summaryService, randomProfileGenerator, asyncExecutor, getLogger());
        // conversationSessionManager is intentionally NOT recreated here: doing so would silently
        // drop any conversation currently open mid-chat when an admin runs /aihuman reload. This
        // does mean a changed interaction.chat-capture-timeout-seconds only takes effect on restart.
        getLogger().info("Configuration reloaded (profile: " + pluginConfig.activeProfile + ").");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);

        if (asyncExecutor != null) {
            asyncExecutor.shutdown();
            try {
                if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    asyncExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                asyncExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (database != null) {
            database.close();
        }
        getLogger().info("AI-Human stopped.");
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public NpcProfileDao getNpcProfileDao() {
        return npcProfileDao;
    }

    public PlayerMemoryDao getPlayerMemoryDao() {
        return playerMemoryDao;
    }

    public DialogHistoryDao getDialogHistoryDao() {
        return dialogHistoryDao;
    }

    public VillageEventDao getVillageEventDao() {
        return villageEventDao;
    }

    public KnowledgeDao getKnowledgeDao() {
        return knowledgeDao;
    }

    public NpcStateDao getNpcStateDao() {
        return npcStateDao;
    }

    public ConversationService getConversationService() {
        return conversationService;
    }

    public ConversationSessionManager getConversationSessionManager() {
        return conversationSessionManager;
    }

    public EnabledNpcRegistry getEnabledNpcRegistry() {
        return enabledNpcRegistry;
    }

    public RandomProfileGenerator getRandomProfileGenerator() {
        return randomProfileGenerator;
    }

    public NpcBehaviorDao getNpcBehaviorDao() {
        return npcBehaviorDao;
    }

    public NpcWaypointDao getNpcWaypointDao() {
        return npcWaypointDao;
    }

    public AutonomousNpcRegistry getAutonomousNpcRegistry() {
        return autonomousNpcRegistry;
    }

    public NpcWaypointRegistry getNpcWaypointRegistry() {
        return npcWaypointRegistry;
    }

    public NpcDialogRenderer getNpcDialogRenderer() {
        return npcDialogRenderer;
    }

    public Pl3xMapIntegration getPl3xMapIntegration() {
        return pl3xMapIntegration;
    }

    public NpcMapSync getNpcMapSync() {
        return npcMapSync;
    }
}
