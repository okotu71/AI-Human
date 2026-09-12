# okotu-npc-ai-engine

Paper/Spigot plugin that connects Citizens-managed NPCs to an Ollama docking
service and gives them layered, persistent memory: a stable character sheet,
per-player relationship/memory that compresses itself over time, shared
village-wide events, per-NPC knowledge boundaries, and a lightweight
emotional state - all folded into a compact prompt (roughly 300-500 tokens,
workable even on a 1-1.5B model).

## Status

This is a **functionally complete skeleton** (follows current Paper/Citizens/
Ollama APIs), but it has **not been built with Maven in the environment that
generated it** (no network access, and no JDK/`javac` either - only a JRE -
in that sandbox). Before going to production:

1. `mvn clean package` locally and fix any compile nits.
2. Double-check the versions in `pom.xml` against what's currently published.
3. Test on a development server before going live.

## Versioning

The jar filename always embeds the Maven version (`<finalName>` in `pom.xml`
uses `${project.artifactId}-${project.version}`), e.g.
`okotu-npc-ai-engine-1.13.jar`. `plugin.yml`'s `version:` field is filled in
automatically at build time from the same value, so **the only place you
need to bump the version for a new release is `pom.xml`**.

## What's new in 1.13

- **Build fix (first real CI compile against actual Citizens API)**:
  `NpcMovementController.navigateTo` called `Navigator#setTarget(Location, boolean)`,
  which doesn't exist on the real Citizens API - the two-argument overload
  is `setTarget(Entity target, boolean aggressive)` (used for chasing an
  entity, e.g. combat), not for a plain `Location`. The single-argument
  `setTarget(Location location)` is the correct call for walking to a
  point, which is all `NpcMovementController` ever needed. Fixed - this was
  flagged as an unverified assumption in the 1.11 README notes, and is
  exactly the kind of thing only a real build against the real Citizens jar
  can catch; no other `Navigator`/`NPC` calls in the autonomous-movement
  code changed. If your build turns up further Citizens API mismatches
  (`isNavigating()`, `cancelNavigation()`, `getLocalParameters().speedModifier(...)`,
  `faceLocation(...)` are the remaining unverified ones), send the compiler
  error and they'll get the same treatment.

## What's new in 1.12

Phase 4 from 1.11, now real: AI-enabled NPCs show up as live markers on a
Pl3xMap web map. Built by adapting the same Pl3xMap bridge already proven
working in a sister project (mc-safeguard's Fabulous Claims/zones map
overlay) rather than guessing at the API from scratch - see that project's
2.0.11-2.0.16 patch notes for the full history of what was tried and why.

- **`com.okotu.npcai.map.Pl3xMapIntegration`** replaces 1.11's
  `NoOpPl3xMapIntegration` stub - same class, same approach as mc-safeguard:
  talks to Pl3xMap **purely via `java.lang.reflect`**, resolved once and
  cached. **Still no Maven dependency on Pl3xMap** (see the comment in
  `pom.xml`) - its published API coordinates/version aren't something this
  environment can verify against a real repository, and getting them wrong
  breaks the build for everyone. If Pl3xMap isn't installed, or its
  installed version doesn't match the reflected method names, the
  integration just logs why (once) and no-ops - it never breaks anything
  else in the plugin.
- Every shape is drawn with the confirmed-live
  `Marker.rectangle(String, double, double, double, double)` - **not** a
  custom icon/point marker. There's no confirmed-safe icon API to build
  against yet (same reasoning as mc-safeguard: guessing at
  `Marker.icon(...)`/`Point.of(...)` risks the exact "argument type
  mismatch" crash documented in that project's 2.0.11-2.0.13 patch notes).
  So each AI-enabled NPC is drawn as a small colored square
  (`npc-map.marker-size`, default 1 block) centered on its position -
  honest and working beats a nicer-looking guess that silently breaks.
- **`NpcMapSync`** (new, main-thread, scheduled every
  `npc-map.refresh-interval-seconds`) draws/updates a marker for every
  spawned, AI-enabled NPC, colored differently for stationary vs autonomous
  ones (`npc-map.stationary` / `npc-map.autonomous` - border+fill+color,
  same shape as mc-safeguard's per-layer style config), with a tooltip
  showing the NPC's name. Markers for NPCs that stop qualifying (disabled,
  despawned, removed) are cleaned up the next cycle - a simplified version
  of mc-safeguard's ADD/UPDATE/DELETE claim-diffing, without needing the
  content-hash step (an NPC's position differs almost every cycle anyway,
  so there's little to gain from stricter change detection here).
- `integrations.pl3xmap.enabled` (already in config.yml since 1.11) is
  still the master on/off switch; the new `npc-map:` section controls how
  the markers look and how often they refresh - both default to sensible
  values, so turning `pl3xmap.enabled: true` on a server with Pl3xMap
  installed should just work with zero further tuning.
- `/okotunpc version` now reports `pl3xmap=true (present=true/false)` -
  `present` reflects `Pl3xMapIntegration#isPresent()`, i.e. whether Pl3xMap
  is actually installed, enabled, and successfully resolved via reflection
  right now - not just whether the config flag is on.

### Known limitation carried over from mc-safeguard's own notes

Toggling `npc-map.debug` (or any `npc-map.*` value) via `/okotunpc reload`
updates the *style* immediately (`NpcMapStyle` re-reads config.yml live),
but the `Pl3xMapIntegration`/`NpcMapSync` instances themselves are built
once at startup and not recreated on reload - same deliberate tradeoff
already made for `ConversationSessionManager`'s timeout in 1.06, to avoid
tearing down live state on every `/okotunpc reload`. A full restart picks
up a changed `debug` flag or a Pl3xMap install that appeared after startup.

## What's new in 1.11

The big one: NPCs can now move around the world on their own and approach
players themselves, instead of standing still waiting for a click or for a
player to walk into a fixed detection radius. Built as three additive
subsystems on top of the existing 1.10 codebase, not a rewrite - stationary
AI NPCs (`autonomous` left off) behave exactly like 1.10, unaffected.

### 1) Autonomous wandering + destination safety

- New `npc_behavior_config` table (one row per NPC, only meaningful once
  `autonomous` is true): behavior type, home point, wander radius/min/max
  distance, detection radius, and per-NPC safety overrides.
- `/okotunpc autonomous <npcId> on|off` - turns wandering on (capturing the
  NPC's current position as its home/anchor point) or off (keeps all its
  settings, just stops moving). Requires the NPC to already be AI-enabled
  (`/okotunpc enable`) - autonomy is a layer on top of that, not a
  replacement.
- `/okotunpc wander <npcId> <radius> <minDistance> <maxDistance>` and
  `/okotunpc behavior <npcId> <WANDER|VILLAGE|TRAVEL|GUARD|FOLLOW>` tune a
  specific NPC. **Only `WANDER` is actually implemented** - the other four
  are accepted and stored (so the schema/commands don't need to change
  again later) but currently behave like WANDER. The command warns you
  about this when you set one.
- The model never decides individual steps. `NpcMovementController` picks
  one destination per wander cycle (a random point within
  `[wander-min-distance, wander-max-distance]` of home, never further than
  `wander-radius`), `NpcWorldSafety` validates it against the live world
  (rejects lava, deep water, fire, cactus, and drops taller than
  `max-safe-fall-blocks`) before it's ever handed to Citizens'
  `Navigator.setTarget(...)` - which then does the actual
  pathfinding/walking/obstacle-avoidance, same as it always has. If nothing
  safe is found in `destination-attempts` tries, the NPC just stays put and
  tries again next cycle, instead of walking somewhere unsafe.
- All of this is configurable under `interaction.autonomous` in
  config.yml, with per-NPC overrides via the commands above.

### 2) Approach/talk state machine

- New `NpcActivityState`: `WANDERING` → `APPROACHING` → `TALKING` → back to
  `WANDERING`, driven by a new `NpcBehaviorManager` scheduled task
  (separate from `ProximityGreetingTask`, which keeps handling stationary
  NPCs exactly as before).
- While `WANDERING`, the NPC also watches for a player entering its
  `detection-radius` (shared `NpcPerception` logic, same idea as
  `ProximityGreetingTask`'s proximity check) - if one shows up, it stops
  wandering and walks toward them (`APPROACHING`), stopping
  `approach-stop-distance` blocks away and facing them once close enough
  (or once Navigator gives up), then opens a conversation exactly the way
  `ProximityGreetingTask` already does (same `ConversationSessionManager` +
  `ConversationService#greetApproachingPlayer`, including the 1.09 fix that
  restarts the reply-capture window once the greeting actually arrives).
  Once the conversation session closes, the NPC goes back to `WANDERING`.
- Reuses all of the existing memory/relationship/summary machinery - an
  autonomous NPC approaching you for the first time in a while can still
  say "welcome back" the same way a stationary one always could.

### 3) Dialogue visible in the world

- New `NpcDialogRenderer`: shows the NPC's line as floating text above its
  head using Paper's **native `TextDisplay` entity** (available since
  1.19.4) - no hologram plugin dependency needed. Controlled by
  `dialog.world-bubble` in config.yml (on by default, `duration-seconds`,
  `view-range`, `height-offset`).
- This is *in addition to* the chat message the player already gets, not a
  replacement - wired into all three trigger paths (right-click, stationary
  proximity greeting, and the new autonomous approach/talk flow), so it's
  consistent everywhere an NPC speaks.

### 4) Pl3xMap integration - scaffolded, NOT functional yet

Deliberately **not implemented** this round: Pl3xMap's marker API differs
enough across versions that guessing at it risked shipping an integration
that silently doesn't work on your setup. What's here instead:
`integrations/Pl3xMapIntegration` (interface) + `NoOpPl3xMapIntegration`
(does nothing, logs one clear warning at startup if you turn it on) so the
extension point exists and compiles cleanly without requiring Pl3xMap to be
installed at all. `integrations.pl3xmap.enabled` defaults to `false`. Tell
me your exact Pl3xMap version/API and this becomes a real, working
implementation instead of a stub.

### Things worth testing carefully before relying on this in production

- **Citizens Navigator API calls** (`getNavigator().setTarget/isNavigating/
  cancelNavigation`, `getLocalParameters().speedModifier`,
  `npc.faceLocation`) are long-stable parts of the Citizens API, but
  `pom.xml`'s `citizens.version` is still the placeholder
  `2.0.35-SNAPSHOT` from the very first version of this project - confirm
  the real version you're running before relying on autonomous movement,
  and watch the console on first enable for any Navigator-related errors.
- **Ground-finding uses `World#getHighestBlockAt`**, which works well
  above ground but doesn't understand caves/overhangs - an autonomous NPC
  wandering near a cave entrance may pick odd destinations. Fine for
  typical village/overworld wandering; revisit if you want NPCs
  underground.
- **`TextDisplay#setViewRange`** is a scalar on the server's own entity
  tracking range, not a literal block count - `dialog.world-bubble.view-range`
  is treated as an approximation against a ~64-block baseline. Tune it
  empirically if bubbles seem to (dis)appear at the wrong distance.
- The autonomous subsystem adds one more per-tick-interval main-thread scan
  (`NpcBehaviorManager`, separate from `ProximityGreetingTask`'s own scan) -
  negligible for a handful of autonomous NPCs, worth watching if you enable
  it for a large number at once.

## What's new in 1.10

- **Critical bug fix: fresh databases couldn't start at all.**
  `CREATE TABLE npc_profiles` failed with
  `BLOB, TEXT, GEOMETRY or JSON column 'personality' can't have a default value`
  - MySQL doesn't allow a literal `DEFAULT` on `TEXT`/`BLOB`/`JSON` columns
  (only `VARCHAR` and other fixed-size types support that). `personality`
  and `background` were declared `TEXT NOT NULL DEFAULT ''`, which is
  invalid syntax. Since `CREATE TABLE IF NOT EXISTS` is one atomic
  statement, this failure meant **none** of the six tables got created on a
  brand-new database (the other five all have a foreign key back to
  `npc_profiles`, so they never even got attempted). Fixed to
  `TEXT NOT NULL` (no default) in `schema.sql` and both `sql/okotu_npc_ai*.sql`
  reference copies - `NpcProfileDao.insert()` already always supplies an
  explicit value for both columns, so nothing else needed to change. If you
  hit this on 1.09, there's nothing to migrate: the tables were never
  created in the first place, so updating the jar and restarting is enough -
  `CREATE TABLE IF NOT EXISTS` will simply succeed this time.
- **`/okotunpc info <npcId>` now shows the plugin version and author** right
  under the NPC's name line (`okotu-npc-ai-engine vX.XX by okotu71`).
- **`/okotunpc version` now shows the GitHub link**
  (`https://github.com/okotu71/NPC-AI-Engine`), and the plugin's own
  `author`/`website` fields in `plugin.yml` are set too (so Bukkit's
  built-in `/plugins` and `/version` commands show proper attribution as well).
- **Ollama performance tuning, expanded.** `OllamaClient` now sends the
  full common set of llama.cpp/Ollama runtime options instead of just
  `num_predict`/`temperature`: `num_ctx` (context window - kept small for
  dialogue since the assembled prompt is designed to stay short, larger for
  summaries via a separate `summary-num-ctx`), `num_batch` (prompt-eval
  batch size), `num_thread` (pin to your docking's actual CPU allocation
  instead of relying on auto-detection on a shared host), `num_gpu` (0 by
  default, matching the CPU-only egg from earlier - raise it if your
  docking has GPU support), and `top_k`/`top_p`/`repeat_penalty` (sampling
  knobs that also help avoid the model rambling into repetition and
  burning through its `num_predict` budget for nothing). All new keys live
  under `ollama:` in config.yml, documented inline, and default to values
  that change nothing versus 1.09 until you tune them (except `num_ctx`,
  which is a genuine new speed lever - lower than most models' own default
  context size, matched to how short this plugin's prompts are designed to
  be). `/okotunpc version` shows all of them.

## What's new in 1.09

- **Default `ollama:` tuning updated**: `default-model` is now
  `"Qwen2.5:0.5b"` (a smaller/faster model than the previous
  `qwen2.5:1.5b`), `num-predict` down from 64 to 24, `summary-num-predict`
  down from 400 to 250 - all other `ollama:` settings unchanged.
  **Casing note**: Ollama model tags are conventionally lowercase
  (`qwen2.5:0.5b`); this is set to `Qwen2.5:0.5b` (capital Q) exactly as
  requested. If NPCs start failing with a "model not found"-style error
  after upgrading, run `ollama list` on the docking server and check the
  tag's exact casing there - config.yml has a comment calling this out too.
  The Java-side fallback default (only used if the `ollama.default-model`
  key is ever missing from config.yml entirely) uses the safe lowercase
  form regardless, as a defensive fallback.
- **Bug fix: proximity-greeted NPCs often couldn't hear your reply**, even
  though right-click worked fine. Root cause: `ConversationSessionManager`'s
  30-second chat-capture window started counting the moment
  `ProximityGreetingTask` first noticed the player - not when the greeting
  actually reached them. The Ollama round-trip for the greeting can take a
  few seconds (worse right after `keep-alive` expires and the model needs a
  cold load), silently eating into that window before the player even saw
  anything to react to. Right-click never had this problem because the
  "type now" prompt is shown synchronously, with no AI call in between.
  `ProximityGreetingTask` now restarts the timeout right when the greeting
  (or the failure fallback) is actually delivered - guarded so it only
  refreshes a session that's still the same, not-yet-consumed one, to avoid
  accidentally reopening "listening" mode for an unrelated later message if
  the player happened to reply before the greeting text arrived.

## What's new in 1.08

- **Bug fix: memory compression timing out.** Console showed
  `Error checking/compressing memory for npc=X player=Y` /
  `HttpTimeoutException: request timed out` from `SummaryService`. Cause:
  the single `ollama.timeout-ms` (8s by default) was applied to *every*
  Ollama call, including memory-compression summaries - but those generate
  up to `summary-num-predict` (400) tokens instead of the short
  `num-predict` (64) used for normal dialogue, so they legitimately take
  longer and were getting cut off by a timeout tuned for quick replies.
  `OllamaClient.chat()` now takes an explicit timeout per call; dialogue
  keeps using `ollama.timeout-ms` (8s default) while summarization uses a
  new, separate `ollama.summary-timeout-ms` (30s default). If you still see
  timeouts from `SummaryService` after upgrading (e.g. on a slow CPU-only
  box), raise `summary-timeout-ms` further, or lower `summary-num-predict`
  / `conversation.summary-max-words` so there's simply less to generate.

## What's new in 1.07

- **Bug fix: replies weren't reaching Ollama at all on some Paper setups.**
  The NPC would greet first via proximity just fine (that part doesn't
  depend on chat), but typing a reply did nothing - no error, no request
  sent, nothing. Root cause: `NpcBridgeListener` only listened for the
  legacy `org.bukkit.event.player.AsyncPlayerChatEvent`. Paper reworked its
  chat pipeline around 1.19.1 in favor of the Adventure-Component-based
  `io.papermc.paper.event.player.AsyncChatEvent`; depending on the server
  and what other chat-related plugins are installed, the legacy event can
  simply never fire, so the plugin never saw the player's message at all -
  the conversation session opened correctly, it just never got consumed.
  `NpcBridgeListener` now listens for **both** event types and funnels them
  through the same handler. If a server happens to fire both for the same
  physical message, only the first one does anything (the session gets
  cleared as soon as it's consumed, so the second one finds nothing to do) -
  no duplicate requests to Ollama, no double relationship/summary updates.
  No config changes needed, this just works better after upgrading the jar.

## What's new in 1.06

- **AI chat is now opt-in per NPC.** Every Citizens NPC used to gain a random
  personality and start talking automatically the moment a player
  clicked/approached it - fine for a village of speaking villagers, less
  fine if you also have decorative, quest, or vendor NPCs from other systems
  that should stay silent. `npc_profiles` has a new `enabled` column
  (defaults to `0`/false); `NpcBridgeListener` and `ProximityGreetingTask`
  both now check `EnabledNpcRegistry.isEnabled(npcId)` - a fast in-memory
  lookup, not a database query - before doing anything at all for an NPC. A
  disabled NPC is completely inert to this plugin: right-click passes
  through untouched (for whatever other plugin wants it) and it's skipped
  entirely by the proximity scan.
- **`/okotunpc enable <npcId>`** turns AI chat on for an NPC - creates its
  character sheet (via the same random-profile pools as before) if it
  doesn't have one yet, or just flips the flag back on if it was previously
  disabled (keeping whatever backstory/knowledge/relationships it already
  had). **`/okotunpc disable <npcId>`** turns it back off without deleting
  any of that data. Both take a plain numeric NPC id and work identically
  from the server console or in-game - no dependency on Citizens' own
  in-game "selected NPC" concept, which doesn't exist for a console sender.
- **`/okotunpc version`** prints the running plugin version plus every
  AI-related parameter currently loaded (Ollama address, model, keep-alive,
  num-predict, temperature, timeouts/retries, conversation/summary/
  relationship/interaction settings, count of AI-enabled NPCs) - and
  deliberately **never** any `mysql-*` setting (host, port, database,
  username, password, table-prefix). It's meant to be safe to run in front
  of other people or paste into a support channel. Needs no database access,
  so it answers instantly even from console.

## What's new in 1.05

- **Proximity-based conversations, NPC greets first.** Right-click used to be
  the only way to start talking to an NPC - which breaks badly if another
  plugin on your server hijacks right-clicking an NPC/entity for something
  else (the reported case: a "climb on NPC's shoulders" plugin, so right-click
  mounts the player before Citizens/this plugin ever sees the interaction).
  As of 1.05, a new `ProximityGreetingTask` runs on the main thread every
  `interaction.proximity.check-interval-ticks` and, for every spawned
  Citizens NPC, checks for players within `interaction.proximity.radius`
  blocks. The first time a given (NPC, player) pair gets close enough
  (subject to `interaction.proximity.greet-cooldown-minutes` so standing
  there doesn't repeatedly re-trigger it), **the NPC speaks first** - a
  genuine AI-generated, in-character greeting that's aware of relationship
  score and compressed memory (a returning player with a good relationship
  gets greeted by name; a stranger gets a generic hello), not a canned
  string. Right-click still works alongside it by default - set
  `interaction.right-click.enabled: false` in config.yml if it keeps
  conflicting with the other plugin and you want proximity to be the only
  way in.
- **Shared session tracking.** Whichever way a conversation starts (click or
  proximity), it's now tracked by a single `ConversationSessionManager`
  instead of right-click's own private map, so the two triggers never
  fight over "is this player mid-conversation right now". Its timeout
  (`interaction.chat-capture-timeout-seconds`, default 30) also moved out of
  hardcoded Java into config.yml.
- **Unprompted greetings fail silently.** If Ollama doesn't answer in time
  for a proximity greeting, the player just doesn't get greeted (logged at
  FINE, not shown as an error) - unlike a real question they asked, there's
  nothing to apologize for since they didn't ask anything. The conversation
  session still opens either way, so they can just start typing to the NPC
  even if the auto-greeting itself didn't come through.

## What's new in 1.04

- **Faster requests to Ollama.** Every `/api/chat` call now also sends
  `keep_alive` (keeps the model loaded in RAM between requests - avoids a
  slow reload on the next message), and an `options` object with
  `num_predict` (hard cap on how many tokens a reply can generate - the
  single biggest lever for response speed, since NPC replies are meant to be
  short anyway) and `temperature`. All three are configurable under
  `ollama:` in `config.yml` (`keep-alive`, `num-predict`, `temperature`).
  `stream` stays hardcoded to `false` in the code (not exposed as a config
  option) since the plugin always needs the full reply at once.
  - **Important detail**: `num-predict` (default 64) is deliberately
    **not** applied to `SummaryService`'s memory-compression calls - a
    64-token cap would chop a ~200-word summary off mid-sentence.
    `OllamaClient.chat()` now has a second overload taking an explicit
    `num_predict`, and summarization uses its own
    `ollama.summary-num-predict` (default 400). If you raise
    `conversation.summary-max-words` a lot, raise `summary-num-predict`
    to match or summaries will get truncated.
- **No more per-NPC model override.** `npc_profiles.model` is gone. Every
  NPC now uses whatever `ollama.default-model` is configured in
  `config.yml` - change that one value (e.g. to `"gemma3:1b"`) to switch
  every NPC's model at once. If you're upgrading from 1.02/1.03 and had
  per-NPC overrides set, see `sql/MIGRATION_1.03_TO_1.04.sql` - it shows you
  which NPCs had a custom model *before* dropping the column, since that
  information has nowhere left to go.
- **Default fallback text, reworded.** You flagged the same "Neutral,
  curious about newcomers... / An inhabitant of this world..." text again in
  English form - it was already translated in 1.03 (see "About the old
  default text" below for where it actually comes from and why you should
  essentially never see it in practice). Reworded it once more here just to
  make it visibly different from the 1.03 wording, in case you were
  comparing the two builds side by side.

## What's new in 1.03

- **Full English translation.** Every player-facing and NPC-facing string is
  now in English: the prompt sections (`SYSTEM`/`MEMORY`/`KNOWLEDGE`/`CONTEXT`),
  the auto-built character sheet text, fallback messages, chat-capture
  messages, relationship descriptors, the memory-compression instruction sent
  to Ollama, error messages, log lines. **This includes the instruction that
  tells the NPC what language to answer in** - it used to say "answer in
  Italian", it now says "answer in English", so out of the box NPCs will now
  reply in English instead of Italian. If your playerbase is Italian-speaking
  and you only wanted the *code/config* in English, edit the one line in
  `PromptBuilder.buildCharacterSection()` (`"Answer in English, briefly..."`)
  back to Italian, or better, make it a `config.yml` setting - happy to wire
  that up if you'd rather it be configurable than hardcoded either way.
- **Randomized starter profiles.** The first time a player ever talks to a
  given NPC, `RandomProfileGenerator` now picks a role/personality/
  background/profession/speech-style independently at random from the pools
  under `npc-defaults` in `config.yml`, instead of every new NPC getting the
  exact same placeholder text (see "About the old default text" below).
  Village and per-NPC model are deliberately never randomized - see the
  comment in `RandomProfileGenerator`.
- **Default model reconfirmed as `qwen2.5:1.5b`** in both `config.yml`
  (`ollama.default-model`) and the built-in fallback in `PluginConfig` used
  if that key is ever missing from config.yml.
- **Ollama communication debug logging.** New `debug.log-ollama-communication`
  in `config.yml` (off by default). When `true`, `OllamaClient` logs the full
  JSON request sent to Ollama and the raw response received, to the server
  console at INFO level, prefixed `[Ollama DEBUG]`. Very useful while tuning
  prompts/personalities, but noisy and logs conversation content - leave it
  off in normal production use.

### About the old default text ("Neutral, curious about newcomers...")

You asked where that came from: it's `NpcProfile.defaultFor()`, a hardcoded
fallback in the Java code (`model/NpcProfile.java`) - every NPC that didn't
have a row in `npc_profiles` yet fell back to that exact same static text
when a conversation started, which is why every new NPC looked identical.
That method still exists (now translated to English) but as of 1.03 it's
only used as a last-resort fallback if the database is unreachable when a
conversation starts - the actual first-conversation path now goes through
`RandomProfileGenerator` instead, described above.

## What was new in 1.02

A full memory redesign, replacing 1.01's single `npc_character` /
`npc_conversation_log` pair with six tables:

| Table                 | Purpose                                                         | Changes how often |
|------------------------|------------------------------------------------------------------|--------------------|
| `npc_profiles`         | Character sheet (name, role, personality, background, village, profession, speech style, per-NPC model override) | Rarely |
| `npc_player_memory`    | Relationship score + compressed long-term summary per (NPC, player) | Every ~30 messages |
| `npc_dialog_history`   | Raw recent turns only (compressed away periodically)             | Every message, but self-trimming |
| `village_events`       | Shared memory across every NPC in a village, with priority + expiry | As events happen |
| `npc_knowledge`        | Topic -> fact pairs; an NPC only talks about what's listed here  | Rarely |
| `npc_state`            | Emotional state (happiness/fear/anger/fatigue/hunger), 0-100     | Occasionally |

### Memory compression, the core new mechanic

Every `conversation.summary-trigger-messages` (default 30) raw messages
accumulated for a given (NPC, player) pair, `SummaryService` asks Ollama to
fold them - plus the existing summary, if any - into an updated summary
capped at `conversation.summary-max-words` (default 200) words, saves it to
`npc_player_memory.summary`, and deletes the raw rows from
`npc_dialog_history`. This is what keeps long-term memory small no matter how
long a player has been talking to an NPC. The "ULTIMI MESSAGGI" section of
the prompt is unaffected by this - it always shows the last
`conversation.recent-messages` (default 20) raw turns, tracked independently.

### Prompt structure

`PromptBuilder` assembles, in order: **SYSTEM** (character sheet - or the
`system_prompt` column verbatim if you've authored one by hand), **MEMORIA**
(relationship + compressed summary + notes for this player), **CONOSCENZA**
(the NPC's `npc_knowledge` entries - explicitly instructed to admit not
knowing things outside this list), **CONTESTO** (active `village_events` for
the NPC's village + a short mood description derived from `npc_state`). The
recent raw messages are passed separately as chat history to Ollama's
`/api/chat`, not flattened into the system prompt text.

### Dynamic relationships

`npc_player_memory.relationship_score` (-100..100, clamped) drives the
MEMORIA section's tone description. Adjust it via:
- `/okotunpc relationship <npcId> <player> <delta>` (raw number), or
- `/okotunpc relationship <npcId> <player> action:<key>` using a named delta
  from `relationship.actions` in `config.yml` (e.g. `action:saved-villager`), or
- the public API (`OkotuNpcApi#adjustRelationship` / `#applyRelationshipAction`)
  from another plugin - e.g. hook it into your economy/quest/combat events.

### Ollama docking: prod vs test, same as MySQL

Each profile block (`prod:` / `test:`) in `config.yml` now carries **both**
the MySQL connection **and** the Ollama docking address (`ollama-host` /
`ollama-port`), selected together by the same `active-profile` switch (or
`-Dokotu.profile=test`). This lets you point test traffic at a separate,
disposable Ollama instance if you want.

## Structure

```
src/main/java/com/okotu/npcai/
├── OkotuNpcAiPlugin.java          # bootstrap: wires everything, registers the OkotuNpcApi service
├── config/PluginConfig.java       # typed config.yml reading, incl. prod/test profile (mysql+ollama)
├── db/
│   ├── Database.java               # HikariCP pool + schema.sql application + table-prefix resolution
│   ├── NpcProfileDao.java          # CRUD on npc_profiles
│   ├── PlayerMemoryDao.java        # relationship score, summary, messages-since-summary, last_seen
│   ├── DialogHistoryDao.java       # raw recent turns: insert/fetch/fetchAll/deleteAll/safety-trim
│   ├── VillageEventDao.java        # shared village events: active list, add/remove, expiry cleanup
│   ├── KnowledgeDao.java           # per-NPC topic -> fact pairs
│   ├── NpcStateDao.java            # emotional state
│   └── CleanupTask.java            # periodic safety-trim + expired-events cleanup
├── cache/RecentMessageCache.java   # in-RAM cache of the last N raw turns (Caffeine)
├── model/                          # NpcProfile, PlayerMemory, ConversationEntry, VillageEvent, KnowledgeEntry, NpcState
├── ai/
│   ├── OllamaClient.java           # async HTTP client for /api/chat, retry/timeout
│   └── PromptBuilder.java          # SYSTEM/MEMORIA/CONOSCENZA/CONTESTO assembly
├── service/
│   ├── ConversationService.java    # orchestrates one dialogue turn end to end
│   ├── SummaryService.java         # the memory-compression mechanic
│   └── RelationshipService.java    # score clamping + named actions + qualitative description
├── api/
│   ├── OkotuNpcApi.java            # public interface for other plugins (Bukkit service)
│   └── OkotuNpcApiImpl.java
├── npc/NpcBridgeListener.java      # NPC click -> chat capture -> reply
├── command/OkotuCommand.java       # /okotunpc reload|profile|knowledge|event|relationship|state|info
└── util/RateLimiter.java           # per-player cooldown

sql/
├── okotu_npc_ai.sql                # ready-to-run reference copy for the PROD database
├── okotu_npc_ai_test.sql           # ready-to-run reference copy for the TEST database
└── MIGRATION_1.01_TO_1.02.sql      # data migration from the 1.01 tables into the 1.02 ones
```

## Setup

### 1. Database(s)

`okotu_npc_ai` (prod) and `okotu_npc_ai_test` (test), same as 1.01. Provision
with `sql/okotu_npc_ai.sql` / `sql/okotu_npc_ai_test.sql`, or just let the
plugin create the tables automatically on first startup - either way the
databases themselves must already exist with a user that has privileges:

```sql
CREATE DATABASE IF NOT EXISTS okotu_npc_ai      CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS okotu_npc_ai_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'usr'@'%' IDENTIFIED BY 'psw';
GRANT ALL PRIVILEGES ON okotu_npc_ai.*      TO 'usr'@'%';
GRANT ALL PRIVILEGES ON okotu_npc_ai_test.* TO 'usr'@'%';
FLUSH PRIVILEGES;
```

### 2. Choosing prod vs test

Same mechanism as 1.01, now covering Ollama too:

```yaml
active-profile: "prod"   # or "test"
```

Override without editing the file via `-Dokotu.profile=test` on server
startup (wins over the config value). Switching requires a restart or
`/okotunpc reload` - it doesn't hot-swap an already-open connection mid-session.

### 3. Build

```bash
mvn clean package
```

### 4. First run

If `plugins/OkotuNpcAiEngine/config.yml` doesn't exist yet, the plugin
creates it from the bundled default on startup. Edit it, then
`/okotunpc reload`.

> **About `plugin.yml`**: it's a **build-time** manifest packaged inside the
> jar (it's what tells Bukkit/Paper the plugin exists at all), so it can't be
> "created at runtime" by the plugin itself. If a built jar is missing it,
> that's a packaging issue - see Troubleshooting below.

## In-game usage

**An NPC only responds if an admin has enabled it first** with
`/okotunpc enable <npcId>` (see "What's new in 1.06") - a freshly-placed
Citizens NPC stays silent until then.

Two ways to start talking to an enabled NPC (both on by default, see
`interaction:` in config.yml):

- **Proximity** (new in 1.05): walk within `interaction.proximity.radius`
  blocks of a Citizens NPC - it notices you and greets you first.
- **Right-click**: click the NPC, it invites you to type. Disable via
  `interaction.right-click.enabled: false` if another plugin on your server
  hijacks right-clicking NPCs/entities for something else (see "What's new
  in 1.05").

Either way, the plugin then listens for your next chat message
(`interaction.chat-capture-timeout-seconds`, default 30s), sends it to
Ollama with the assembled prompt, and the reply appears prefixed with the
NPC's name. On timeout/error, a random `fallback.messages` entry is used and
the conversation isn't lost.

## Commands (permission `okotu.npcai.admin`, default op)

- `/okotunpc reload`
- `/okotunpc profile <npcId> <field> <value...>` - fields: `name`, `role`,
  `personality`, `background`, `village`, `profession`, `speech_style`,
  `knowledge`, `system_prompt` (no `model` field since 1.04 - see "What's new
  in 1.04")
- `/okotunpc knowledge add <npcId> <topic> <text...>` /
  `/okotunpc knowledge remove <npcId> <topic>`
- `/okotunpc event add <village> <priority> <expiresHours|never> <summary...>` /
  `/okotunpc event remove <eventId>`
- `/okotunpc relationship <npcId> <player> <delta>` or
  `/okotunpc relationship <npcId> <player> action:<key>`
- `/okotunpc state <npcId> <happiness|fear|anger|fatigue|hunger> <0-100>`
- `/okotunpc enable <npcId>` / `/okotunpc disable <npcId>` - turns AI chat on/off
  for an NPC (console-friendly, see "What's new in 1.06")
- `/okotunpc autonomous <npcId> on|off` - turns autonomous wandering on/off
  (requires AI-enabled first, see "What's new in 1.11")
- `/okotunpc wander <npcId> <radius> <minDistance> <maxDistance>` - per-NPC
  wander tuning
- `/okotunpc behavior <npcId> <WANDER|VILLAGE|TRAVEL|GUARD|FOLLOW>` - only
  WANDER is actually implemented as of 1.11
- `/okotunpc version` - running version + AI parameters only, never MySQL settings
- `/okotunpc info <npcId> [player]`

## Public API for other plugins

`OkotuNpcApi`, registered as a Bukkit service:

```java
RegisteredServiceProvider<OkotuNpcApi> rsp =
        Bukkit.getServicesManager().getRegistration(OkotuNpcApi.class);
if (rsp != null) {
    OkotuNpcApi api = rsp.getProvider();
    api.applyRelationshipAction(npcId, playerUuid, "saved-villager");
    api.addVillageEvent("Oak", 5, "Gli zombie hanno distrutto il ponte.",
            Instant.now().plus(3, ChronoUnit.DAYS));
}
```

All methods are async (`CompletableFuture`) and safe to call from the main
thread.

## Troubleshooting

- **Plugin doesn't load / `plugin.yml` seems missing from the jar**: run
  `unzip -l target/okotu-npc-ai-engine-1.13.jar | grep plugin.yml` after
  building. A stale `target/` from a partial build can cause this - try
  `mvn clean package` from scratch.
- **MySQL connection errors on startup**: check `active-profile` matches a
  section present in `config.yml`, and that the account has privileges on
  the configured database.
- **Memory never compresses / `npc_dialog_history` keeps growing**: check
  the console for `SummaryService` warnings - this almost always means
  Ollama is unreachable or timing out. The safety cleanup job
  (`conversation.max-raw-messages-safety`, default 90) will hard-cap the
  table in the meantime so it can't grow unbounded, but compression will
  keep retrying every turn until Ollama answers.

## Known limitations / suggested next steps

- **Chat capture** listens to both the legacy `AsyncPlayerChatEvent` and
  Paper's `AsyncChatEvent` since 1.07 (see "What's new in 1.07") for broad
  compatibility across server/plugin setups.
- **One active conversation per player** at a time (last NPC that either
  greeted them by proximity or that they clicked).
- **Proximity scan cost**: `ProximityGreetingTask` is O(spawned NPCs x
  online players in that NPC's world) every `check-interval-ticks`. Fine for
  typical village-sized NPC counts; if you have hundreds of NPCs and
  players, raise the interval or consider bucketing by chunk/region instead
  of scanning every NPC every time.
- **`npc_state` moods are generic** (tired/afraid/angry/hungry/happy), not
  target-specific ("angry at the mayor" needs a free-text mechanism like
  `notes` or a `village_events` entry - the numeric state alone can't express *who*).
- **Multi-node failover**: `OllamaClient` still points at a single
  `base-url` per profile; if you run multiple dockings, it'll need node
  selection logic.
- **Summary quality depends on the model**: with very small models (1-1.5B),
  double check compressed summaries occasionally - consider using a larger
  `ollama.summary-model` than your dialogue model if quality matters more
  than compression latency (summarization runs in the background, off the
  player's critical path, so it can afford a slower/bigger model).

## Migrating from 1.05 to 1.06

**Real schema change, and a real behaviour change** - read this before
upgrading a server with existing NPCs that are already talking.

1. Start the plugin once on 1.06 against your existing database (same
   `active-profile`). This creates the new tables/columns it needs
   automatically where they're missing, but `enabled` on `npc_profiles`
   needs an explicit `ALTER TABLE` since the table already exists - the
   automatic `CREATE TABLE IF NOT EXISTS` won't add a column to an existing
   table.
2. Run `sql/MIGRATION_1.05_TO_1.06.sql`. It adds the `enabled` column
   (default `0`).
3. **Every NPC that was already talking under 1.05 will go silent** the
   moment that column lands, because it defaults to disabled. Either:
   - re-enable them one at a time as needed: `/okotunpc enable <npcId>`, or
   - uncomment the bulk `UPDATE npc_profiles SET enabled = 1;` line at the
     bottom of the migration script to flip every existing NPC back on at
     once, preserving the "everything talks" behaviour from 1.05 and
     earlier.
4. Going forward, any newly placed Citizens NPC stays silent until you
   explicitly run `/okotunpc enable <npcId>` for it - there's no config
   toggle to bring back "every NPC talks automatically" as the default,
   this is intentionally opt-in now.

## Migrating from 1.02/1.03 to 1.04

A genuine (small) schema change this time - a real `ALTER TABLE`, not a rename:

1. Update to 1.04 and start the plugin once against your existing database -
   the new schema (without the `model` column) only affects newly-created
   installs; on an existing database the plugin does **not** drop the column
   for you (`CREATE TABLE IF NOT EXISTS` never touches existing tables).
2. Run `sql/MIGRATION_1.03_TO_1.04.sql`: it first shows you which NPCs (if
   any) had a custom per-NPC model set, then drops the `model` column. Review
   that list before running the `ALTER TABLE` - once the column is gone,
   there's no record of which NPCs used to have a different model.
3. From then on, every NPC uses `ollama.default-model` from `config.yml`.

If you're using a non-empty `mysql-table-prefix`, adjust the table name in
the migration script accordingly.

## Migrating from 1.01 to 1.02

**This is a real data migration, not just a rename** (unlike 1.0.0 -> 1.01).
`npc_character` -> `npc_profiles` splits/renames columns, and
`npc_conversation_log` -> `npc_dialog_history` renames `ruolo`/`messaggio` to
`speaker`/`message`. Four brand-new tables have no 1.01 equivalent.

Steps:

1. Update to 1.02 and start the plugin once against your existing database
   (with the same `active-profile` pointing at your current data) - this
   creates the new tables (`CREATE TABLE IF NOT EXISTS`) alongside the old
   1.01 ones, without touching your existing data.
2. Run `sql/MIGRATION_1.01_TO_1.02.sql` against that database. It:
   - copies `npc_character` -> `npc_profiles` (mapping `nome` -> `name`,
     `personalita` -> `personality`, `backstory` -> `background`; `role`,
     `village`, `profession`, `speech_style` come across empty/NULL since
     1.01 never captured them - fill them in afterwards with
     `/okotunpc profile ...`);
   - copies `npc_conversation_log` -> `npc_dialog_history` (straight column
     rename, same data);
   - seeds `npc_player_memory` with `last_seen` inferred from the migrated
     dialog history (relationship score starts at the configured default,
     since 1.01 never tracked it);
   - gives every migrated NPC a default `npc_state` row.
3. Review the migrated data, then **manually** drop the old 1.01 tables
   (commented out at the bottom of the migration script, not run
   automatically):
   ```sql
   DROP TABLE npc_conversation_log;
   DROP TABLE npc_character;
   ```

If you're using a non-empty `mysql-table-prefix`, adjust every table name in
the migration script accordingly before running it.
