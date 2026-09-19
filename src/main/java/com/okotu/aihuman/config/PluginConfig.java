package com.okotu.aihuman.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Typed reading of config.yml. Re-created on every /aihuman reload.
 *
 * <p>Both MySQL credentials AND the Ollama docking address are split into two
 * named profiles, "prod" and "test", selected via {@code active-profile} in
 * config.yml. This can be overridden without editing the file by starting
 * the server with {@code -Dokotu.profile=test} (or "prod"). The system
 * property always wins over the config.yml value.
 */
public class PluginConfig {

    public static final String PROFILE_SYSTEM_PROPERTY = "okotu.profile";

    // --- active profile ---
    public final String activeProfile;

    // --- mysql (resolved from the active profile) ---
    public final String mysqlHost;
    public final int mysqlPort;
    public final String mysqlDatabase;
    public final String mysqlUsername;
    public final String mysqlPassword;
    public final String mysqlTablePrefix;

    // --- mysql (shared across profiles) ---
    public final int mysqlPoolSize;
    public final String mysqlExtraParams;

    // --- ollama (host/port resolved from the active profile, rest shared) ---
    public final String ollamaBaseUrl;
    public final String ollamaDefaultModel;
    public final String ollamaSummaryModel;
    public final long ollamaTimeoutMs;
    public final int ollamaMaxRetries;
    public final long ollamaRetryDelayMs;
    public final String ollamaKeepAlive;
    public final int ollamaNumPredict;
    public final int ollamaSummaryNumPredict;
    public final long ollamaSummaryTimeoutMs;
    public final double ollamaTemperature;
    public final int ollamaNumCtx;
    public final int ollamaSummaryNumCtx;
    public final int ollamaNumBatch;
    public final int ollamaNumThread;
    public final int ollamaNumGpu;
    public final int ollamaTopK;
    public final double ollamaTopP;
    public final double ollamaRepeatPenalty;

    // --- conversation / memory compression ---
    public final int recentMessages;
    public final int summaryTriggerMessages;
    public final int summaryMaxWords;
    public final String summaryPromptTemplate;
    public final int maxRawMessagesSafety;
    public final long cleanupIntervalMinutes;
    public final long cacheMaxEntries;
    public final long cacheExpireAfterMinutes;
    public final int villageEventsLimit;
    public final int knowledgeLimit;

    // --- relationship ---
    public final int relationshipMin;
    public final int relationshipMax;
    public final int relationshipDefault;
    public final Map<String, Integer> relationshipActions;

    // --- interaction (how a conversation gets started) ---
    public final boolean rightClickTriggerEnabled;
    public final boolean proximityTriggerEnabled;
    public final double proximityRadius;
    public final long proximityCheckIntervalTicks;
    public final long proximityGreetCooldownMs;
    public final long chatCaptureTimeoutMs;

    // --- interaction.autonomous (1.11+) ---
    public final boolean autonomousEnabled;
    public final long autonomousCheckIntervalTicks;
    public final int autonomousDefaultWanderRadius;
    public final int autonomousDefaultWanderMinDistance;
    public final int autonomousDefaultWanderMaxDistance;
    public final double autonomousDefaultDetectionRadius;
    public final double autonomousApproachStopDistance;
    public final double autonomousWalkSpeed;
    public final int autonomousMaxElevationChange;
    public final boolean autonomousPreferPaths;
    public final int autonomousPathSearchRadius;
    public final long autonomousStuckTimeoutMs;
    public final double autonomousStuckMinProgressDistance;
    public final boolean autonomousAvoidLava;
    public final boolean autonomousAvoidDeepWater;
    public final boolean autonomousAvoidCliffs;
    public final boolean autonomousAvoidFire;
    public final int autonomousMaxSafeFallBlocks;
    public final int autonomousMaxWaterDepth;
    public final int autonomousDestinationAttempts;

    // --- dialog.world-bubble (1.11+) ---
    public final boolean worldBubbleEnabled;
    public final long worldBubbleDurationTicks;
    public final double worldBubbleViewRange;
    public final double worldBubbleHeightOffset;

    // --- integrations (1.11+) ---
    public final boolean pl3xMapEnabled;
    public final boolean npcMapEnabled;
    public final boolean npcMapDebug;
    public final long npcMapRefreshIntervalTicks;
    public final double npcMapMarkerSize;
    public final boolean npcMapIconMode;

    // --- rate limit ---
    public final long perPlayerCooldownMs;

    // --- fallback ---
    public final boolean fallbackEnabled;
    public final List<String> fallbackMessages;

    // --- randomized starter profile pools (npc-defaults) ---
    public final List<String> npcDefaultRoles;
    public final List<String> npcDefaultPersonalities;
    public final List<String> npcDefaultBackgrounds;
    public final List<String> npcDefaultProfessions;
    public final List<String> npcDefaultSpeechStyles;

    // --- debug ---
    public final boolean debugLogOllamaCommunication;

    public PluginConfig(Plugin plugin) {
        FileConfiguration cfg = plugin.getConfig();
        Logger logger = plugin.getLogger();

        String configuredProfile = cfg.getString("active-profile", "prod");
        String resolvedProfile = System.getProperty(PROFILE_SYSTEM_PROPERTY, configuredProfile);
        if (!"prod".equalsIgnoreCase(resolvedProfile) && !"test".equalsIgnoreCase(resolvedProfile)) {
            logger.warning("Unknown profile '" + resolvedProfile + "' (expected 'prod' or 'test'), "
                    + "falling back to 'prod'.");
            resolvedProfile = "prod";
        }
        this.activeProfile = resolvedProfile.toLowerCase();

        ConfigurationSection profileSection = cfg.getConfigurationSection(activeProfile);
        if (profileSection == null) {
            logger.severe("Profile '" + activeProfile + "' is missing from config.yml! "
                    + "Falling back to built-in defaults, please check your configuration.");
        }

        this.mysqlHost = getFromProfile(profileSection, "mysql-host", "localhost");
        this.mysqlPort = profileSection != null ? profileSection.getInt("mysql-port", 3306) : 3306;
        this.mysqlDatabase = getFromProfile(profileSection, "mysql-database",
                "prod".equals(activeProfile) ? "okotu_npc_ai" : "okotu_npc_ai_test");
        this.mysqlUsername = getFromProfile(profileSection, "mysql-username", "okotu");
        this.mysqlPassword = getFromProfile(profileSection, "mysql-password", "");
        this.mysqlTablePrefix = getFromProfile(profileSection, "mysql-table-prefix", "");

        this.mysqlPoolSize = cfg.getInt("mysql-pool-size", 10);
        this.mysqlExtraParams = cfg.getString("mysql-extra-params", "");

        String ollamaHost = getFromProfile(profileSection, "ollama-host", "127.0.0.1");
        int ollamaPort = profileSection != null ? profileSection.getInt("ollama-port", 11434) : 11434;
        this.ollamaBaseUrl = "http://" + ollamaHost + ":" + ollamaPort;

        this.ollamaDefaultModel = cfg.getString("ollama.default-model", "qwen2.5:0.5b");
        String summaryModel = cfg.getString("ollama.summary-model", "");
        this.ollamaSummaryModel = (summaryModel == null || summaryModel.isBlank())
                ? this.ollamaDefaultModel : summaryModel;
        this.ollamaTimeoutMs = cfg.getLong("ollama.timeout-ms", 8000);
        this.ollamaMaxRetries = cfg.getInt("ollama.max-retries", 1);
        this.ollamaRetryDelayMs = cfg.getLong("ollama.retry-delay-ms", 500);
        this.ollamaKeepAlive = cfg.getString("ollama.keep-alive", "30m");
        this.ollamaNumPredict = cfg.getInt("ollama.num-predict", 24);
        this.ollamaSummaryNumPredict = cfg.getInt("ollama.summary-num-predict", 250);
        this.ollamaSummaryTimeoutMs = cfg.getLong("ollama.summary-timeout-ms", 30000);
        this.ollamaTemperature = cfg.getDouble("ollama.temperature", 0.7);
        this.ollamaNumCtx = cfg.getInt("ollama.num-ctx", 1024);
        this.ollamaSummaryNumCtx = cfg.getInt("ollama.summary-num-ctx", 2048);
        this.ollamaNumBatch = cfg.getInt("ollama.num-batch", 512);
        this.ollamaNumThread = cfg.getInt("ollama.num-thread", 0);
        this.ollamaNumGpu = cfg.getInt("ollama.num-gpu", 0);
        this.ollamaTopK = cfg.getInt("ollama.top-k", 40);
        this.ollamaTopP = cfg.getDouble("ollama.top-p", 0.9);
        this.ollamaRepeatPenalty = cfg.getDouble("ollama.repeat-penalty", 1.1);

        this.recentMessages = cfg.getInt("conversation.recent-messages", 20);
        this.summaryTriggerMessages = cfg.getInt("conversation.summary-trigger-messages", 30);
        this.summaryMaxWords = cfg.getInt("conversation.summary-max-words", 200);
        this.summaryPromptTemplate = cfg.getString("conversation.summary-prompt",
                "Summarize this conversation in at most {max_words} words, keeping only the important facts.");
        this.maxRawMessagesSafety = cfg.getInt("conversation.max-raw-messages-safety", 90);
        this.cleanupIntervalMinutes = cfg.getLong("conversation.cleanup-interval-minutes", 10);
        this.cacheMaxEntries = cfg.getLong("conversation.cache-max-entries", 5000);
        this.cacheExpireAfterMinutes = cfg.getLong("conversation.cache-expire-after-minutes", 30);
        this.villageEventsLimit = cfg.getInt("conversation.village-events-limit", 5);
        this.knowledgeLimit = cfg.getInt("conversation.knowledge-limit", 20);

        this.relationshipMin = cfg.getInt("relationship.min", -100);
        this.relationshipMax = cfg.getInt("relationship.max", 100);
        this.relationshipDefault = cfg.getInt("relationship.default", 0);
        this.relationshipActions = readRelationshipActions(cfg.getConfigurationSection("relationship.actions"));

        this.rightClickTriggerEnabled = cfg.getBoolean("interaction.right-click.enabled", true);
        this.proximityTriggerEnabled = cfg.getBoolean("interaction.proximity.enabled", true);
        this.proximityRadius = cfg.getDouble("interaction.proximity.radius", 4.0);
        this.proximityCheckIntervalTicks = cfg.getLong("interaction.proximity.check-interval-ticks", 20);
        this.proximityGreetCooldownMs = cfg.getLong("interaction.proximity.greet-cooldown-minutes", 5) * 60_000L;
        this.chatCaptureTimeoutMs = cfg.getLong("interaction.chat-capture-timeout-seconds", 30) * 1000L;

        this.autonomousEnabled = cfg.getBoolean("interaction.autonomous.enabled", true);
        this.autonomousCheckIntervalTicks = cfg.getLong("interaction.autonomous.check-interval-ticks", 40);
        this.autonomousDefaultWanderRadius = cfg.getInt("interaction.autonomous.default-wander-radius", 500);
        this.autonomousDefaultWanderMinDistance =
                cfg.getInt("interaction.autonomous.default-wander-min-distance", 30);
        this.autonomousDefaultWanderMaxDistance =
                cfg.getInt("interaction.autonomous.default-wander-max-distance", 150);
        this.autonomousDefaultDetectionRadius =
                cfg.getDouble("interaction.autonomous.default-detection-radius", 15);
        this.autonomousApproachStopDistance = cfg.getDouble("interaction.autonomous.approach-stop-distance", 2.5);
        this.autonomousWalkSpeed = cfg.getDouble("interaction.autonomous.walk-speed", 1.0);
        this.autonomousMaxElevationChange = cfg.getInt("interaction.autonomous.max-elevation-change", 20);
        this.autonomousPreferPaths = cfg.getBoolean("interaction.autonomous.prefer-paths", true);
        this.autonomousPathSearchRadius = cfg.getInt("interaction.autonomous.path-search-radius", 6);
        this.autonomousStuckTimeoutMs =
                cfg.getLong("interaction.autonomous.safety.stuck-timeout-seconds", 15) * 1000L;
        this.autonomousStuckMinProgressDistance =
                cfg.getDouble("interaction.autonomous.safety.stuck-min-progress-distance", 2.0);
        this.autonomousAvoidLava = cfg.getBoolean("interaction.autonomous.safety.avoid-lava", true);
        this.autonomousAvoidDeepWater = cfg.getBoolean("interaction.autonomous.safety.avoid-deep-water", true);
        this.autonomousAvoidCliffs = cfg.getBoolean("interaction.autonomous.safety.avoid-cliffs", true);
        this.autonomousAvoidFire = cfg.getBoolean("interaction.autonomous.safety.avoid-fire", true);
        this.autonomousMaxSafeFallBlocks = cfg.getInt("interaction.autonomous.safety.max-safe-fall-blocks", 3);
        this.autonomousMaxWaterDepth = cfg.getInt("interaction.autonomous.safety.max-water-depth", 2);
        this.autonomousDestinationAttempts = cfg.getInt("interaction.autonomous.safety.destination-attempts", 8);

        this.worldBubbleEnabled = cfg.getBoolean("dialog.world-bubble.enabled", true);
        this.worldBubbleDurationTicks = cfg.getLong("dialog.world-bubble.duration-seconds", 6) * 20L;
        this.worldBubbleViewRange = cfg.getDouble("dialog.world-bubble.view-range", 24);
        this.worldBubbleHeightOffset = cfg.getDouble("dialog.world-bubble.height-offset", 0.4);

        this.pl3xMapEnabled = cfg.getBoolean("integrations.pl3xmap.enabled", false);
        this.npcMapEnabled = cfg.getBoolean("npc-map.enabled", true);
        this.npcMapDebug = cfg.getBoolean("npc-map.debug", false);
        this.npcMapRefreshIntervalTicks = cfg.getLong("npc-map.refresh-interval-seconds", 10) * 20L;
        this.npcMapMarkerSize = cfg.getDouble("npc-map.marker-size", 1.0);
        this.npcMapIconMode = cfg.getBoolean("npc-map.icon-mode", true);

        this.perPlayerCooldownMs = cfg.getLong("rate-limit.per-player-cooldown-ms", 3000);

        this.fallbackEnabled = cfg.getBoolean("fallback.enabled", true);
        this.fallbackMessages = cfg.getStringList("fallback.messages");

        this.npcDefaultRoles = cfg.getStringList("npc-defaults.roles");
        this.npcDefaultPersonalities = cfg.getStringList("npc-defaults.personalities");
        this.npcDefaultBackgrounds = cfg.getStringList("npc-defaults.backgrounds");
        this.npcDefaultProfessions = cfg.getStringList("npc-defaults.professions");
        this.npcDefaultSpeechStyles = cfg.getStringList("npc-defaults.speech-styles");

        this.debugLogOllamaCommunication = cfg.getBoolean("debug.log-ollama-communication", false);
    }

    private static String getFromProfile(ConfigurationSection section, String key, String fallback) {
        return section != null ? section.getString(key, fallback) : fallback;
    }

    private static Map<String, Integer> readRelationshipActions(ConfigurationSection section) {
        Map<String, Integer> actions = new LinkedHashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                actions.put(key, section.getInt(key, 0));
            }
        }
        return actions;
    }

    /** Delta configured for a named relationship action, or null if unknown. */
    public Integer relationshipActionDelta(String actionKey) {
        return relationshipActions.get(actionKey);
    }
}
