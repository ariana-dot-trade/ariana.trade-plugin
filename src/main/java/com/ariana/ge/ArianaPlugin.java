package com.ariana.ge;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Hitsplat;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemComposition;
import net.runelite.api.InventoryID;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.WorldType;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import javax.imageio.ImageIO;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.MenuEntry;
import net.runelite.api.widgets.WidgetInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
    name = "ariana.trade",
    description = "Syncs bank, inventory, equipment, and GE offers to ariana.trade via WebSocket",
    tags = {"bank", "ge", "ariana", "sync", "flipping"}
)
public class ArianaPlugin extends Plugin
{
    private static final String RELAY_URL = "wss://api.ariana.trade/relay/plugin";
    private static final int PLUGIN_VERSION = 8;
    private static final int MESSAGE_QUEUE_MAX = 500;
    private static final long MESSAGE_QUEUE_MAX_BYTES = 10 * 1024 * 1024; // 10MB
    private static final long RECONNECT_DELAY_MS = 5_000;
    private static final long RECONNECT_DELAY_MAX_MS = 60_000;
    private static final int KEEPALIVE_INTERVAL_TICKS = 83; // ~50 seconds (0.6s/tick) — well under Cloudflare's ~100s idle timeout
    private int ticksSinceLastSend = 0;

    @Inject
    private Client client;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ArianaConfig config;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ConfigManager configManager;

    // Relay WebSocket client
    private volatile WebSocket relaySocket = null;
    private volatile boolean relayConnected = false;
    private volatile long reconnectDelay = RECONNECT_DELAY_MS;
    private volatile boolean shuttingDown = false;
    private Thread reconnectThread = null;

    // Sidebar panel
    // Sidebar panel removed — all config via wrench icon settings
    private NavigationButton navButton;

    // Message queue for reconnect flush
    private final ConcurrentLinkedQueue<String> messageQueue = new ConcurrentLinkedQueue<>();
    private volatile long messageQueueBytes = 0;

    // Auto-launch guard
    private volatile boolean hasLaunchedThisSession = false;

    // Game data
    private volatile Map<Integer, Integer> bankItems = new HashMap<>();
    private volatile long lastBankUpdate = 0;

    private volatile Map<Integer, Integer> inventoryItems = new HashMap<>();
    private volatile long lastInventoryUpdate = 0;

    private volatile Map<String, int[]> equipmentSlots = new HashMap<>();
    private volatile long lastEquipmentUpdate = 0;

    private volatile OfferSnapshot[] geOffers = new OfferSnapshot[8];
    private volatile long lastGeUpdate = 0;

    private volatile String playerName = "";
    private volatile boolean loggedIn = false;
    private volatile boolean isMember = true; // default to true, updated on login
    private volatile int loginContainerReadCountdown = -1; // ticks until we try direct container reads after login

    // ---- EIE event pipeline state ----
    // tickBuffer accumulates EIE events each tick; drained by the flush block at the end of onGameTick
    private final List<String> tickBuffer = new ArrayList<>();
    // groundItems: tile-packed-key -> GroundItemData
    private final ConcurrentHashMap<Long, GroundItemData> groundItems = new ConcurrentHashMap<>();
    // pendingPickups: tileKey -> itemId we clicked "Take" on this tick
    private final ConcurrentHashMap<Long, Integer> pendingPickups = new ConcurrentHashMap<>();
    // xpAccumulator: skill-name -> {delta, ticksRemaining}
    private final ConcurrentHashMap<String, int[]> xpAccumulator = new ConcurrentHashMap<>();
    // previousXp: skill-name -> last seen xp value
    private final ConcurrentHashMap<String, Integer> previousXp = new ConcurrentHashMap<>();
    // prevEquipmentSlots: slot-name -> [id, qty] — snapshot for ammo diff
    private volatile Map<String, int[]> prevEquipmentSlots = new HashMap<>();
    // prevInventoryItems: itemId -> qty — snapshot for inventory diff events
    private volatile Map<Integer, Integer> prevInventoryItems = new HashMap<>();
    private volatile boolean inCombat = false;
    private volatile int combatTickCount = 0;
    private volatile int lastRegionId = -1;
    private volatile int currentGameTick = 0;
    private volatile int lastAnimTick = -1;
    private static final int COMBAT_IDLE_TICKS = 10;
    private static final int XP_BATCH_WINDOW = 5; // ticks before XP batch flushes

    // Phase A2: HP/Prayer/Spec tracking
    private volatile int prevHp = -1;
    private volatile int prevMaxHp = -1;
    private volatile int prevPrayer = -1;
    private volatile int prevMaxPrayer = -1;
    private volatile int prevSpecPercent = -1;  // 0-1000 (1000 = 100%)

    // Phase A2: Rune pouch tracking
    // Varbit rune index → item ID mapping
    private static final int[] RUNE_POUCH_RUNE_VARBITS = { 29, 1622, 1623 };
    private static final int[] RUNE_POUCH_AMOUNT_VARBITS = { 1624, 1625, 1626 };
    // Divine rune pouch has a 4th slot
    private static final int RUNE_POUCH_RUNE4_VARBIT = 14285;
    private static final int RUNE_POUCH_AMOUNT4_VARBIT = 14286;

    // Varbit rune index → item ID lookup
    private static final int[] RUNE_INDEX_TO_ITEM_ID = {
        0,     // 0 = empty
        556,   // 1 = Air rune
        555,   // 2 = Water rune
        557,   // 3 = Earth rune
        554,   // 4 = Fire rune
        558,   // 5 = Mind rune
        562,   // 6 = Chaos rune
        560,   // 7 = Death rune
        565,   // 8 = Blood rune
        564,   // 9 = Cosmic rune
        561,   // 10 = Nature rune
        563,   // 11 = Law rune
        559,   // 12 = Body rune
        566,   // 13 = Soul rune
        9075,  // 14 = Astral rune
        4695,  // 15 = Mist rune
        4698,  // 16 = Mud rune
        4696,  // 17 = Dust rune
        4699,  // 18 = Lava rune
        4694,  // 19 = Steam rune
        4697,  // 20 = Smoke rune
        21880, // 21 = Wrath rune
        28929, // 22 = Sunfire rune
    };

    private volatile String prevRunePouchSnapshot = "";  // JSON cache for change detection

    private static final String[] EQUIP_SLOT_NAMES = {
        "HEAD", "CAPE", "AMULET", "WEAPON", "BODY",
        "SHIELD", "ARMS", "LEGS", "HAIR", "GLOVES",
        "BOOTS", "JAW", "RING", "AMMO"
    };

    // ================================================================
    // PLUGIN LIFECYCLE
    // ================================================================

    @Override
    protected void startUp() throws Exception
    {
        log.info("ariana.trade v{} started!", PLUGIN_VERSION);
        for (int i = 0; i < 8; i++) { geOffers[i] = OfferSnapshot.EMPTY; }
        startServers();

        // No sidebar panel — all settings live in the wrench icon config.
        // Just add a navigation button that opens ariana.trade when clicked.
        BufferedImage icon;
        try
        {
            icon = ImageIO.read(getClass().getResourceAsStream("icon.png"));
        }
        catch (Exception e)
        {
            log.warn("Ariana: failed to load icon, using fallback");
            icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++)
                    icon.setRGB(x, y, 0xFF00BCD4);
        }

        navButton = NavigationButton.builder()
            .tooltip("ariana.trade")
            .icon(icon)
            .priority(5)
            .popup(Map.of("Open ariana.trade", () -> launchAriana()))
            .build();

        clientToolbar.addNavigation(navButton);
    }

    @Override
    protected void shutDown() throws Exception
    {
        log.info("ariana.trade stopped!");
        clientToolbar.removeNavigation(navButton);
        stopServers();
    }

    @Provides
    ArianaConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ArianaConfig.class);
    }

    // ================================================================
    // RELAY WEBSOCKET CLIENT
    // ================================================================

    private void startServers()
    {
        shuttingDown = false;
        connectRelay();
    }

    private void stopServers()
    {
        shuttingDown = true;
        if (reconnectThread != null)
        {
            reconnectThread.interrupt();
            reconnectThread = null;
        }
        WebSocket ws = relaySocket;
        if (ws != null)
        {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin shutting down"); }
            catch (Exception e) { /* ignore */ }
            relaySocket = null;
        }
        relayConnected = false;
        // connection state change (no panel)
    }

    private void connectRelay()
    {
        String token = config.relayToken();
        if (token == null || token.trim().isEmpty())
        {
            log.info("Ariana: no relay token configured — not connecting");
            return;
        }

        // Connect without token in URL — token is sent as first message after connection
        // for better security (tokens in URLs can leak via server logs, referrer headers, etc.)
        log.info("Ariana: connecting to relay {}", RELAY_URL);

        try
        {
            HttpClient httpClient = HttpClient.newHttpClient();
            httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(RELAY_URL), new RelayListener(token.trim()))
                .exceptionally(ex -> {
                    log.warn("Ariana: relay connect failed: {}", ex.getMessage());
                    scheduleReconnect();
                    return null;
                });
        }
        catch (Exception e)
        {
            log.warn("Ariana: relay connect error: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect()
    {
        if (shuttingDown) return;
        long delay = reconnectDelay;
        reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_DELAY_MAX_MS);
        log.info("Ariana: reconnecting in {}ms", delay);

        reconnectThread = new Thread(() -> {
            try
            {
                Thread.sleep(delay);
                if (!shuttingDown) connectRelay();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        });
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private class RelayListener implements WebSocket.Listener
    {
        private final StringBuilder textBuffer = new StringBuilder();
        private final String authToken;

        RelayListener(String authToken)
        {
            this.authToken = authToken;
        }

        @Override
        public void onOpen(WebSocket webSocket)
        {
            log.info("Ariana: relay connected — sending auth token");

            // Send auth token as first message (post-connect authentication)
            // Data is NOT sent here — we wait for auth_ok in onText before sending state
            try
            {
                webSocket.sendText("{\"type\":\"auth\",\"token\":\"" + authToken + "\"}", true);
                log.info("Ariana: auth token sent, waiting for auth_ok before sending data");
            }
            catch (Exception e)
            {
                log.warn("Ariana: failed to send auth token: {}", e.getMessage());
                scheduleReconnect();
                return;
            }

            relaySocket = webSocket;
            reconnectDelay = RECONNECT_DELAY_MS; // reset backoff
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
        {
            textBuffer.append(data);
            if (last)
            {
                String msg = textBuffer.toString().trim();
                textBuffer.setLength(0);

                // Check for auth_ok — server confirmed auth, now safe to send data
                if (!relayConnected && msg.contains("\"type\":\"auth_ok\""))
                {
                    log.info("Ariana: auth_ok received — sending initial state");
                    relayConnected = true;

                    // Flush buffered messages
                    int flushed = 0;
                    String queued;
                    while ((queued = messageQueue.poll()) != null)
                    {
                        try { webSocket.sendText(queued, true); flushed++; }
                        catch (Exception e) { log.debug("Flush error: {}", e.getMessage()); }
                    }
                    messageQueueBytes = 0;
                    if (flushed > 0) log.info("Ariana: flushed {} buffered messages", flushed);

                    // Send current state
                    try
                    {
                        String state = "{\"type\":\"connected\"," + buildAllDataInner() + "}";
                        webSocket.sendText(state, true);
                    }
                    catch (Exception e)
                    {
                        log.error("Ariana: failed to send initial state: {}", e.getMessage());
                    }
                }
                else if (msg.contains("\"type\":\"auth_error\""))
                {
                    log.warn("Ariana: auth rejected by server: {}", msg);
                    relayConnected = false;
                    relaySocket = null;
                    scheduleReconnect();
                }
                else
                {
                    handleRelayMessage(webSocket, msg);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason)
        {
            log.info("Ariana: relay disconnected (code={}, reason={})", statusCode, reason);
            relaySocket = null;
            relayConnected = false;
            // connection state change (no panel)
            if (!shuttingDown) scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error)
        {
            log.warn("Ariana: relay error: {}", error.getMessage());
            relaySocket = null;
            relayConnected = false;
            // connection state change (no panel)
            if (!shuttingDown) scheduleReconnect();
        }
    }

    private void handleRelayMessage(WebSocket webSocket, String msg)
    {
        log.debug("Relay message: {}", msg);
        switch (msg)
        {
            case "health":
                try { webSocket.sendText("{\"type\":\"health\"," + buildHealthInner() + "}", true); } catch (Exception e) { /* ignore */ }
                break;
            case "bank":
                // NOTE: Do NOT call client.getItemContainer() here — this runs on the WS thread.
                // Bank data is populated by onItemContainerChanged (client thread) and
                // direct-read on login in onGameStateChanged.
                try { webSocket.sendText("{\"type\":\"bank\"," + buildBankInner() + "}", true); } catch (Exception e) { /* ignore */ }
                break;
            case "inventory":
                try { webSocket.sendText("{\"type\":\"inventory\"," + buildInventoryInner() + "}", true); } catch (Exception e) { /* ignore */ }
                break;
            case "equipment":
                try { webSocket.sendText("{\"type\":\"equipment\"," + buildEquipmentInner() + "}", true); } catch (Exception e) { /* ignore */ }
                break;
            case "ge":
                try { webSocket.sendText("{\"type\":\"ge\"," + buildGeInner() + "}", true); } catch (Exception e) { /* ignore */ }
                break;
            case "all":
                try { webSocket.sendText("{\"type\":\"all\"," + buildAllDataInner() + "}", true); } catch (Exception e) { /* ignore */ }
                break;
            default:
                try { webSocket.sendText("{\"type\":\"error\",\"message\":\"Unknown command: " + escapeJson(msg) + "\"}", true); } catch (Exception e) { /* ignore */ }
                break;
        }
    }

    /**
     * Send a message to the relay. If not connected, buffer for replay on reconnect.
     */
    private void broadcast(String json)
    {
        WebSocket ws = relaySocket;
        if (ws == null || !relayConnected)
        {
            queueMessage(json);
            return;
        }
        try
        {
            ws.sendText(json, true);
            ticksSinceLastSend = 0;
        }
        catch (Exception e)
        {
            log.debug("Ariana: send error: {}", e.getMessage());
            queueMessage(json);
        }
    }

    /**
     * Add a message to the reconnect flush queue.
     * Injects "buffered":true and "originalTimestamp" fields.
     * Enforces cap of MESSAGE_QUEUE_MAX messages or MESSAGE_QUEUE_MAX_BYTES bytes.
     */
    private void queueMessage(String json)
    {
        long now = System.currentTimeMillis();

        // Inject buffered metadata into the JSON
        // Insert after the opening '{' — all our messages start with {"type":...}
        String buffered = "{\"buffered\":true,\"originalTimestamp\":" + now + "," + json.substring(1);

        int msgBytes = buffered.length() * 2; // rough estimate (Java chars are 2 bytes)

        // Enforce size limits — drop oldest if full
        while (messageQueue.size() >= MESSAGE_QUEUE_MAX ||
               (messageQueueBytes + msgBytes > MESSAGE_QUEUE_MAX_BYTES && !messageQueue.isEmpty()))
        {
            String dropped = messageQueue.poll();
            if (dropped != null)
            {
                messageQueueBytes -= (dropped.length() * 2);
            }
        }

        messageQueue.offer(buffered);
        messageQueueBytes += msgBytes;

        if (messageQueue.size() % 50 == 0)
        {
            log.debug("Message queue: {} messages, ~{}KB", messageQueue.size(), messageQueueBytes / 1024);
        }
    }

    // ================================================================
    // AUTO-LAUNCH
    // ================================================================

    /**
     * Open ariana.trade in the default browser.
     * Called by the panel "Open ariana.trade" button — always opens.
     */
    private void launchAriana()
    {
        try
        {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(config.arianaUrl()));
            hasLaunchedThisSession = true;
            log.info("Ariana: opened in default browser");
        }
        catch (Exception e)
        {
            log.error("Ariana: failed to open browser: {}", e.getMessage());
        }
    }

    /**
     * Auto-launch ariana.trade, but only once per session.
     * Prevents duplicate windows when both auto-launch-on-login and
     * auto-launch-on-GE are enabled.
     */
    private void autoLaunchAriana()
    {
        if (hasLaunchedThisSession) return;
        launchAriana();
    }

    // ================================================================
    // EVENT SUBSCRIBERS — Push data to connected clients
    // ================================================================

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        int id = event.getContainerId();
        log.debug("Ariana: ItemContainerChanged containerId={} (BANK={}, INV={})",
            id, InventoryID.BANK.getId(), InventoryID.INVENTORY.getId());
        if (id == InventoryID.BANK.getId())
        {
            log.info("Ariana: BANK container changed — updating bank data");
            updateBankData(event.getItemContainer());
            log.info("Ariana: Bank now has {} items", bankItems.size());
            broadcast("{\"type\":\"bank\"," + buildBankInner() + "}");
        }
        else if (id == InventoryID.INVENTORY.getId())
        {
            Map<Integer, Integer> oldInv = new HashMap<>(inventoryItems);
            updateInventoryData(event.getItemContainer());
            broadcast("{\"type\":\"inventory\"," + buildInventoryInner() + "}");

            // EIE: Compute inventory diff for ConsumptionEngine
            if (!oldInv.isEmpty()) // Skip first observation (no previous state)
            {
                Map<Integer, Integer> newInv = inventoryItems;
                StringBuilder dec = new StringBuilder();
                boolean firstDec = true;
                for (Map.Entry<Integer, Integer> e : oldInv.entrySet())
                {
                    int itemId = e.getKey();
                    int oldQty = e.getValue();
                    int newQty = newInv.getOrDefault(itemId, 0);
                    if (newQty < oldQty)
                    {
                        if (!firstDec) dec.append(",");
                        dec.append("\"").append(itemId).append("\":").append(oldQty - newQty);
                        firstDec = false;
                    }
                }
                StringBuilder inc = new StringBuilder();
                boolean firstInc = true;
                for (Map.Entry<Integer, Integer> e : newInv.entrySet())
                {
                    int itemId = e.getKey();
                    int newQty = e.getValue();
                    int oldQty = oldInv.getOrDefault(itemId, 0);
                    if (newQty > oldQty)
                    {
                        if (!firstInc) inc.append(",");
                        inc.append("\"").append(itemId).append("\":").append(newQty - oldQty);
                        firstInc = false;
                    }
                }
                if (!firstDec || !firstInc)
                {
                    queueEieEvent("\"type\":\"inv_change\"" +
                        ",\"decreases\":{" + dec + "}" +
                        ",\"increases\":{" + inc + "}" +
                        ",\"timestamp\":" + System.currentTimeMillis());
                }
            }
        }
        else if (id == InventoryID.EQUIPMENT.getId())
        {
            Map<String, int[]> oldEquip = new HashMap<>(equipmentSlots);
            updateEquipmentData(event.getItemContainer());
            broadcast("{\"type\":\"equipment\"," + buildEquipmentInner() + "}");

            // EIE: Emit equipment change event for ChargeTracker / DegradationTracker
            int[] oldWeapon = oldEquip.get("WEAPON");
            int[] newWeapon = equipmentSlots.get("WEAPON");
            int[] oldAmmo   = oldEquip.get("AMMO");
            int[] newAmmo   = equipmentSlots.get("AMMO");

            boolean weaponChanged = !arraysEqual(oldWeapon, newWeapon);
            boolean ammoChanged   = !arraysEqual(oldAmmo, newAmmo);

            if (weaponChanged || ammoChanged)
            {
                StringBuilder sb = new StringBuilder();
                sb.append("\"type\":\"equip_change\"");
                if (newWeapon != null) {
                    sb.append(",\"weaponId\":").append(newWeapon[0]);
                    sb.append(",\"weaponQty\":").append(newWeapon[1]);
                } else {
                    sb.append(",\"weaponId\":-1");
                }
                if (newAmmo != null) {
                    sb.append(",\"ammoId\":").append(newAmmo[0]);
                    sb.append(",\"ammoQty\":").append(newAmmo[1]);
                } else {
                    sb.append(",\"ammoId\":-1");
                }
                if (oldWeapon != null) {
                    sb.append(",\"prevWeaponId\":").append(oldWeapon[0]);
                }
                sb.append(",\"timestamp\":").append(System.currentTimeMillis());
                queueEieEvent(sb.toString());
            }
        }
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
    {
        int slot = event.getSlot();
        GrandExchangeOffer offer = event.getOffer();
        if (offer == null) return;
        if (offer.getState() == GrandExchangeOfferState.EMPTY
            && client.getGameState() != GameState.LOGGED_IN) return;

        OfferSnapshot prev = geOffers[slot];
        geOffers[slot] = OfferSnapshot.from(offer, slot);
        lastGeUpdate = System.currentTimeMillis();

        log.debug("GE slot {} updated: {} {} x{} @ {}gp (filled {}/{})",
            slot, geOffers[slot].state, geOffers[slot].itemId,
            geOffers[slot].totalQuantity, geOffers[slot].price,
            geOffers[slot].quantityFilled, geOffers[slot].totalQuantity);

        broadcast("{\"type\":\"ge\"," + buildGeInner() + "}");

        // Update sidebar panel with current offers
        // offers updated (sent via relay)

        // Track completed flips for session profit
        OfferSnapshot cur = geOffers[slot];
        if (cur.state.equals("BOUGHT") || cur.state.equals("SOLD"))
        {
            if (prev != null && !prev.state.equals(cur.state))
            {
                // A buy completion is negative (money out), sell completion is positive (money in)
                long profit = cur.state.equals("SOLD") ? cur.spent : -cur.spent;
                // flip profit tracked via relay (no panel)
            }
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            loggedIn = true;
            nameResolutionTicks = 0;
            
            try {
                java.util.EnumSet<WorldType> worldTypes = client.getWorldType();
                isMember = worldTypes != null && worldTypes.contains(WorldType.MEMBERS);
                log.info("World type: {} (members={})", worldTypes, isMember);
            } catch (Exception e) {
                isMember = true;
            }
            
            if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
            {
                playerName = client.getLocalPlayer().getName();
                // panel removed — RSN is sent via WebSocket to ariana.trade
            }

            // Reset session profit on new login
            // session reset (no panel)

            GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
            if (offers != null)
            {
                for (int i = 0; i < offers.length && i < 8; i++)
                    if (offers[i] != null)
                        geOffers[i] = OfferSnapshot.from(offers[i], i);
                lastGeUpdate = System.currentTimeMillis();
                log.info("Loaded {} GE offers on login", offers.length);
                // offers updated (sent via relay)
            }

            // Schedule delayed container reads — containers aren't loaded yet at LOGGED_IN time.
            // We read them a few ticks later in onGameTick when the client has fully loaded.
            loginContainerReadCountdown = 5; // 5 game ticks (~3 seconds)

            broadcast("{\"type\":\"login\"," + buildAllDataInner() + "}");

            // Auto-launch Ariana on login if configured (once per session)
            if (config.autoLaunchOnLogin())
            {
                autoLaunchAriana();
            }
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            loggedIn = false;
            broadcast("{\"type\":\"logout\",\"playerName\":\"" + escapeJson(playerName) + "\"}");
            playerName = "";
            hasLaunchedThisSession = false; // Reset so auto-launch re-triggers on next login

            // Clear all EIE state so it doesn't bleed into the next session
            xpAccumulator.clear();
            previousXp.clear();
            groundItems.clear();
            pendingPickups.clear();
            tickBuffer.clear();
            prevEquipmentSlots = new HashMap<>();
            prevInventoryItems = new HashMap<>();
            inCombat = false;
            combatTickCount = 0;
            lastRegionId = -1;
            lastAnimTick = -1;
            prevHp = -1;
            prevMaxHp = -1;
            prevPrayer = -1;
            prevMaxPrayer = -1;
            prevSpecPercent = -1;
            prevRunePouchSnapshot = "";
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        // Grand Exchange interface is widget group 465
        if (event.getGroupId() == 465 && config.autoLaunchOnGE())
        {
            autoLaunchAriana();
        }
    }

    // ================================================================
    // RIGHT-CLICK MENU — "Open in Ariana" for inventory/bank items
    // ================================================================

    private static final String MENU_OPTION = "Open in ariana";

    // Bank region IDs — common bank areas in OSRS
    private static final Set<Integer> BANK_REGIONS = new HashSet<>(Arrays.asList(
        12598, // Grand Exchange
        12342, // Varrock West Bank
        12853, // Varrock East Bank
        12850, // Lumbridge Castle
        11828, // Falador East Bank
        11827, // Falador West Bank
        10806, // Draynor Village
        11310, // Al Kharid
        10548, // Edgeville
        10290, // Barbarian Village (not a bank but close to Edgeville)
        13105, // Seers' Village
        10547, // Catherby
        11062, // Ardougne North
        10291, // Ardougne South
        11571, // Yanille
        12082, // Castle Wars
        9520,  // Pest Control
        14642, // Prifddinas
        12344, // Canifis
        13613, // Burgh de Rott
        6457,  // Mor Ul Rek (TzHaar)
        5536,  // Nardah
        6967,  // Shilo Village
        7505,  // Hosidius
        6713,  // Lovakengj
        6970,  // Shayzien
        7227,  // Arceuus
        6459,  // Piscarillius (Port Piscarilius)
        9275,  // Myths' Guild
        9023,  // Crafting Guild
        11057, // Cooking Guild
        11083, // Farming Guild
        12600, // Clan Hall
        9772,  // Ferox Enclave
        14484, // Fossil Island
        13658  // Darkmeyer
    ));

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        if (!config.enableContextMenu()) return;

        // Only add to inventory, bank, and equipment right-click menus
        // BANK_INVENTORY_ITEMS_CONTAINER = inventory panel while bank interface is open
        int groupId = WidgetInfo.TO_GROUP(event.getActionParam1());
        if (groupId != WidgetInfo.INVENTORY.getGroupId()
            && groupId != WidgetInfo.BANK_ITEM_CONTAINER.getGroupId()
            && groupId != WidgetInfo.BANK_INVENTORY_ITEMS_CONTAINER.getGroupId()
            && groupId != WidgetInfo.EQUIPMENT.getGroupId())
        {
            return;
        }

        int rawItemId = event.getItemId();
        if (rawItemId <= 0) return;
        // Canonicalize: convert noted/placeholder IDs to the real base item ID
        int itemId = itemManager.canonicalize(rawItemId);

        // Prevent duplicate entries — check if we already added our option
        for (MenuEntry entry : client.getMenuEntries())
        {
            if (MENU_OPTION.equals(entry.getOption())) return;
        }

        // Bank-only mode: skip if not near a bank
        if (config.contextMenuBankOnly() && !BANK_REGIONS.contains(lastRegionId))
        {
            return;
        }

        client.createMenuEntry(-1)
            .setOption(MENU_OPTION)
            .setTarget(event.getTarget())
            .setType(MenuAction.RUNELITE)
            .onClick(e -> openItemInAriana(itemId));
    }

    private void openItemInAriana(int itemId)
    {
        if (itemId <= 0) return;

        boolean sentViaRelay = false;

        // Try WebSocket relay first — navigates the already-open ariana.trade tab
        if (relayConnected && relaySocket != null)
        {
            try
            {
                String msg = "{\"type\":\"navigate_item\",\"itemId\":" + itemId + "}";
                relaySocket.sendText(msg, true);
                ticksSinceLastSend = 0;
                sentViaRelay = true;
                log.info("Ariana: sent navigate_item {} via relay", itemId);
            }
            catch (Exception e)
            {
                log.warn("Ariana: relay send failed (dead socket?), triggering reconnect: {}", e.getMessage());
                // Socket is dead — clean up and reconnect
                relaySocket = null;
                relayConnected = false;
                scheduleReconnect();
            }
        }

        // Always open in browser as fallback — if relay worked, this just brings
        // the existing tab to front via the ?item= deep link (ariana.trade deduplicates).
        // If relay failed, this ensures the user still gets the item page.
        if (!sentViaRelay)
        {
            try
            {
                String url = "https://ariana.trade?item=" + itemId;
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                log.info("Ariana: opened item {} in browser (relay unavailable)", itemId);
            }
            catch (Exception e)
            {
                log.error("Ariana: failed to open item in browser: {}", e.getMessage());
            }
        }
    }

    private int nameResolutionTicks = 0;
    private static final int MAX_NAME_TICKS = 20;

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        currentGameTick++;

        // Reset name resolution counter while not logged in so it re-runs on next login
        if (!loggedIn) nameResolutionTicks = 0;

        // --- Name resolution (no early return — flush must always execute) ---
        if (loggedIn
                && (playerName == null || playerName.isEmpty() || playerName.equals("???"))
                && nameResolutionTicks < MAX_NAME_TICKS)
        {
            nameResolutionTicks++;
            if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
            {
                String name = client.getLocalPlayer().getName();
                if (name != null && !name.isEmpty() && !name.equals("???"))
                {
                    playerName = name;
                    nameResolutionTicks = MAX_NAME_TICKS;
                    log.debug("Player name resolved on tick {}: {}", nameResolutionTicks, playerName);
                    // RSN resolved (no panel)
                    broadcast("{\"type\":\"health\"," + buildHealthInner() + "}");
                }
            }
        }

        // --- Delayed container read after login ---
        // Containers aren't ready at LOGGED_IN time; read them a few ticks later
        if (loginContainerReadCountdown > 0)
        {
            loginContainerReadCountdown--;
            if (loginContainerReadCountdown == 0)
            {
                loginContainerReadCountdown = -1;
                log.info("Ariana: Delayed container read — reading inv/equip/bank from client");
                boolean changed = false;
                try
                {
                    ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
                    if (inv != null && inventoryItems.isEmpty()) { updateInventoryData(inv); changed = true; log.info("Ariana: Delayed read got {} inventory items", inventoryItems.size()); }
                    ItemContainer equip = client.getItemContainer(InventoryID.EQUIPMENT);
                    if (equip != null && equipmentSlots.isEmpty()) { updateEquipmentData(equip); changed = true; log.info("Ariana: Delayed read got {} equipment slots", equipmentSlots.size()); }
                    ItemContainer bank = client.getItemContainer(InventoryID.BANK);
                    if (bank != null && bankItems.isEmpty()) { updateBankData(bank); changed = true; log.info("Ariana: Delayed read got {} bank items", bankItems.size()); }
                    else if (bank == null) { log.info("Ariana: Bank container still null (bank not opened this session)"); }
                }
                catch (Exception e) { log.warn("Ariana: Delayed container read failed: {}", e.getMessage()); }
                if (changed)
                {
                    broadcast("{\"type\":\"connected\"," + buildAllDataInner() + "}");
                }
            }
        }

        // --- WebSocket keepalive: send a ping if no data sent recently ---
        ticksSinceLastSend++;
        if (ticksSinceLastSend >= KEEPALIVE_INTERVAL_TICKS && relayConnected && relaySocket != null)
        {
            try
            {
                relaySocket.sendText("{\"type\":\"ping\"}", true);
                ticksSinceLastSend = 0;
            }
            catch (Exception e)
            {
                log.debug("Ariana: keepalive ping failed: {}", e.getMessage());
                // Connection is dead — trigger reconnect
                relaySocket = null;
                relayConnected = false;
                scheduleReconnect();
            }
        }

        // --- EIE: Drain XP accumulator (timed batching) + subsystems ---
        detectRegionChange();
        detectAmmoConsumption();
        detectLevelChanges();
        // Poll rune pouch more often during combat (every 2 ticks vs 5)
        int rpInterval = inCombat ? 2 : 5;
        if (currentGameTick % rpInterval == 0) detectRunePouchChanges();
        processXpBatchTimers();

        // Combat cooldown: exit combat after N idle ticks
        if (inCombat)
        {
            combatTickCount++;
            if (combatTickCount >= COMBAT_IDLE_TICKS)
            {
                inCombat = false;
                combatTickCount = 0;
            }
        }

        // --- EIE: Evict stale ground items every 500 ticks ---
        if (currentGameTick % 500 == 0)
        {
            long cutoff = System.currentTimeMillis() - 120_000L; // 2 minutes
            groundItems.entrySet().removeIf(e -> e.getValue().spawnedAt < cutoff);
            pendingPickups.clear();
        }

        // --- EIE: Flush tick buffer ---
        if (!tickBuffer.isEmpty())
        {
            StringBuilder batch = new StringBuilder("{\"type\":\"eie_batch\",\"tick\":");
            batch.append(client.getTickCount());
            batch.append(",\"playerName\":\"").append(escapeJson(playerName)).append("\"");
            batch.append(",\"inCombat\":").append(inCombat);
            batch.append(",\"events\":[");
            for (int i = 0; i < tickBuffer.size(); i++)
            {
                if (i > 0) batch.append(",");
                batch.append("{");
                batch.append(tickBuffer.get(i));
                batch.append("}");
            }
            batch.append("]}");
            broadcast(batch.toString());
            tickBuffer.clear();
        }
    }

    // ================================================================
    // F3 HELPERS
    // ================================================================

    private void detectRegionChange()
    {
        Player local = client.getLocalPlayer();
        if (local == null) return;
        WorldPoint wp = local.getWorldLocation();
        if (wp == null) return;
        int regionId = wp.getRegionID();
        if (regionId == lastRegionId) return;
        lastRegionId = regionId;
        // Reset combat on region change (teleport / new area)
        inCombat = false;
        combatTickCount = 0;
        queueEieEvent("\"type\":\"region_change\",\"regionId\":" + regionId +
            ",\"timestamp\":" + System.currentTimeMillis());
    }

    private void detectAmmoConsumption()
    {
        Map<String, int[]> prev = prevEquipmentSlots;
        Map<String, int[]> curr = equipmentSlots;
        if (prev == null || curr == null) return;

        int[] prevAmmo = prev.get("AMMO");
        int[] currAmmo = curr.get("AMMO");

        // Ammo slot disappeared entirely
        if (prevAmmo != null && currAmmo == null)
        {
            int consumed = prevAmmo[1];
            if (consumed > 0 && consumed <= 500)
            {
                queueEieEvent("\"type\":\"ammo_used\",\"itemId\":" + prevAmmo[0] +
                    ",\"qty\":" + consumed + ",\"timestamp\":" + System.currentTimeMillis());
            }
        }
        // Same ammo item, quantity dropped
        else if (prevAmmo != null && currAmmo != null && prevAmmo[0] == currAmmo[0])
        {
            int consumed = prevAmmo[1] - currAmmo[1];
            if (consumed > 0 && consumed <= 500)
            {
                queueEieEvent("\"type\":\"ammo_used\",\"itemId\":" + currAmmo[0] +
                    ",\"qty\":" + consumed + ",\"timestamp\":" + System.currentTimeMillis());
            }
        }

        // Check WEAPON slot for thrown consumables (chinchompas, obsidian ring)
        int[] prevWeapon = prev.get("WEAPON");
        int[] currWeapon = curr.get("WEAPON");
        if (prevWeapon != null && currWeapon != null
            && prevWeapon[0] == currWeapon[0]  // Same weapon
            && THROWN_WEAPON_IDS.contains(prevWeapon[0])
            && currWeapon[1] < prevWeapon[1])  // Qty decreased
        {
            int consumed = prevWeapon[1] - currWeapon[1];
            if (consumed > 0 && consumed <= 500)
            {
                queueEieEvent("\"type\":\"ammo_used\",\"itemId\":" + prevWeapon[0] +
                    ",\"qty\":" + consumed +
                    ",\"cost\":" + ((long) itemManager.getItemPrice(prevWeapon[0]) * consumed) +
                    ",\"timestamp\":" + System.currentTimeMillis());
            }
        }

        // Snapshot current for next tick comparison
        prevEquipmentSlots = new HashMap<>(curr);
    }

    private void detectLevelChanges()
    {
        Player local = client.getLocalPlayer();
        if (local == null) return;

        int hp        = client.getBoostedSkillLevel(Skill.HITPOINTS);
        int maxHp     = client.getRealSkillLevel(Skill.HITPOINTS);
        int prayer    = client.getBoostedSkillLevel(Skill.PRAYER);
        int maxPrayer = client.getRealSkillLevel(Skill.PRAYER);
        int spec      = client.getVarpValue(300);  // VarPlayer SPECIAL_ATTACK_PERCENT (0-1000)

        boolean changed = false;
        StringBuilder sb = new StringBuilder();
        sb.append("\"type\":\"levels\"");

        if (hp != prevHp || maxHp != prevMaxHp) {
            sb.append(",\"hp\":").append(hp).append(",\"maxHp\":").append(maxHp);
            changed = true;
        }
        if (prayer != prevPrayer || maxPrayer != prevMaxPrayer) {
            sb.append(",\"prayer\":").append(prayer).append(",\"maxPrayer\":").append(maxPrayer);
            changed = true;
        }
        if (spec != prevSpecPercent) {
            sb.append(",\"spec\":").append(spec);
            // Detect spec weapon usage: spec dropped by 250 (25%) or 500 (50%)
            if (prevSpecPercent > 0 && spec < prevSpecPercent) {
                int drop = prevSpecPercent - spec;
                sb.append(",\"specUsed\":").append(drop);
                // Include weapon ID so browser can identify spec type
                // HP heal:  12926 (toxic blowpipe), 11806 (SGS), 26233 (ancient godsword)
                // Prayer:   11806 (SGS), 24424 (eldritch nightmare staff), 11061 (ancient mace)
                int[] weapon = equipmentSlots.get("WEAPON");
                if (weapon != null) {
                    sb.append(",\"specWeaponId\":").append(weapon[0]);
                }
            }
            changed = true;
        }

        if (changed) {
            sb.append(",\"timestamp\":").append(System.currentTimeMillis());
            queueEieEvent(sb.toString());
        }

        prevHp = hp;
        prevMaxHp = maxHp;
        prevPrayer = prayer;
        prevMaxPrayer = maxPrayer;
        prevSpecPercent = spec;
    }

    private void detectRunePouchChanges()
    {
        StringBuilder snapshot = new StringBuilder();
        StringBuilder eventData = new StringBuilder();
        eventData.append("\"type\":\"rune_pouch\",\"runes\":[");
        boolean hasAny = false;
        int slotCount = 0;

        // Slots 1-3 (standard rune pouch)
        for (int i = 0; i < RUNE_POUCH_RUNE_VARBITS.length; i++) {
            int runeIdx = client.getVarbitValue(RUNE_POUCH_RUNE_VARBITS[i]);
            int qty     = client.getVarbitValue(RUNE_POUCH_AMOUNT_VARBITS[i]);
            snapshot.append(runeIdx).append(":").append(qty).append(",");
            if (runeIdx > 0 && runeIdx < RUNE_INDEX_TO_ITEM_ID.length && qty > 0) {
                if (slotCount > 0) eventData.append(",");
                eventData.append("{\"itemId\":").append(RUNE_INDEX_TO_ITEM_ID[runeIdx]);
                eventData.append(",\"qty\":").append(qty).append("}");
                slotCount++;
                hasAny = true;
            }
        }

        // Slot 4 (divine rune pouch)
        int rune4Idx = client.getVarbitValue(RUNE_POUCH_RUNE4_VARBIT);
        int rune4Qty = client.getVarbitValue(RUNE_POUCH_AMOUNT4_VARBIT);
        snapshot.append(rune4Idx).append(":").append(rune4Qty);
        if (rune4Idx > 0 && rune4Idx < RUNE_INDEX_TO_ITEM_ID.length && rune4Qty > 0) {
            if (slotCount > 0) eventData.append(",");
            eventData.append("{\"itemId\":").append(RUNE_INDEX_TO_ITEM_ID[rune4Idx]);
            eventData.append(",\"qty\":").append(rune4Qty).append("}");
            hasAny = true;
        }

        String snapStr = snapshot.toString();
        if (!snapStr.equals(prevRunePouchSnapshot)) {
            prevRunePouchSnapshot = snapStr;
            if (hasAny) {
                eventData.append("],\"timestamp\":").append(System.currentTimeMillis());
                queueEieEvent(eventData.toString());
            }
        }
    }

    private void processXpBatchTimers()
    {
        if (xpAccumulator.isEmpty()) return;

        List<String> toFlush = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : xpAccumulator.entrySet())
        {
            int[] state = entry.getValue(); // [0]=delta, [1]=ticksRemaining
            state[1]--;
            if (state[1] <= 0) toFlush.add(entry.getKey());
        }

        long ts = System.currentTimeMillis();
        for (String skill : toFlush)
        {
            int[] state = xpAccumulator.remove(skill);
            if (state != null && state[0] > 0)
            {
                queueEieEvent("\"type\":\"xp_gain\",\"skill\":\"" + escapeJson(skill) + "\"" +
                    ",\"xp\":" + state[0] + ",\"timestamp\":" + ts);
            }
        }
    }

    // ================================================================
    // DATA UPDATE METHODS
    // ================================================================

    private void updateBankData(ItemContainer bank)
    {
        if (bank == null) return;
        Map<Integer, Integer> newBank = new HashMap<>();
        Item[] items = bank.getItems();
        int skippedPlaceholders = 0;

        for (Item item : items)
        {
            int id = item.getId();
            int qty = item.getQuantity();
            if (id == -1 || qty <= 0) continue;

            ItemComposition comp = itemManager.getItemComposition(id);
            if (comp != null)
            {
                if (comp.getPlaceholderTemplateId() != -1) { skippedPlaceholders++; continue; }
                String name = comp.getName();
                if (name != null && name.toLowerCase().contains("placeholder")) { skippedPlaceholders++; continue; }
            }

            int realId = itemManager.canonicalize(id);
            if (realId <= 0) continue;
            newBank.merge(realId, qty, Integer::sum);
        }

        bankItems = newBank;
        lastBankUpdate = System.currentTimeMillis();
        log.info("Bank updated: {} unique items ({} placeholders skipped)", bankItems.size(), skippedPlaceholders);
    }

    private void updateInventoryData(ItemContainer inventory)
    {
        if (inventory == null) return;
        Map<Integer, Integer> newInv = new HashMap<>();
        for (Item item : inventory.getItems())
        {
            int id = item.getId();
            int qty = item.getQuantity();
            if (id == -1 || qty <= 0) continue;
            int realId = itemManager.canonicalize(id);
            if (realId <= 0) continue;
            newInv.merge(realId, qty, Integer::sum);
        }
        inventoryItems = newInv;
        lastInventoryUpdate = System.currentTimeMillis();
    }

    private void updateEquipmentData(ItemContainer equipment)
    {
        if (equipment == null) return;
        Map<String, int[]> newEquip = new HashMap<>();
        Item[] items = equipment.getItems();
        for (int i = 0; i < EQUIP_SLOT_NAMES.length && i < items.length; i++)
        {
            Item item = items[i];
            if (item == null) continue;
            int id = item.getId();
            int qty = item.getQuantity();
            if (id == -1 || qty <= 0) continue;
            newEquip.put(EQUIP_SLOT_NAMES[i], new int[]{id, qty});
        }
        equipmentSlots = newEquip;
        lastEquipmentUpdate = System.currentTimeMillis();
    }

    // ================================================================
    // GE OFFER SNAPSHOT
    // ================================================================

    private static class OfferSnapshot
    {
        static final OfferSnapshot EMPTY = new OfferSnapshot(-1, "EMPTY", -1, 0, 0, 0, 0);

        final int slot;
        final String state;
        final int itemId;
        final int price;
        final int totalQuantity;
        final int quantityFilled;
        final long spent;

        OfferSnapshot(int slot, String state, int itemId, int price,
                      int totalQuantity, int quantityFilled, long spent)
        {
            this.slot = slot;
            this.state = state;
            this.itemId = itemId;
            this.price = price;
            this.totalQuantity = totalQuantity;
            this.quantityFilled = quantityFilled;
            this.spent = spent;
        }

        static OfferSnapshot from(GrandExchangeOffer offer, int slot)
        {
            if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
                return new OfferSnapshot(slot, "EMPTY", -1, 0, 0, 0, 0);

            return new OfferSnapshot(
                slot, offer.getState().name(), offer.getItemId(), offer.getPrice(),
                offer.getTotalQuantity(), offer.getQuantitySold(), offer.getSpent()
            );
        }
    }

    // ================================================================
    // JSON BUILDERS
    // ================================================================

    private String buildHealthInner()
    {
        return "\"status\":\"ok\",\"plugin\":\"ariana.trade\",\"version\":" + PLUGIN_VERSION + "," +
            "\"protocol\":\"relay\"," +
            "\"relay\":\"wss://api.ariana.trade\"," +
            "\"loggedIn\":" + loggedIn + ",\"playerName\":\"" + escapeJson(playerName) + "\"," +
            "\"isMember\":" + isMember + ",\"maxGeSlots\":" + (isMember ? 8 : 3) + "," +
            "\"lastUpdate\":" + lastBankUpdate + "," +
            "\"queuedMessages\":" + messageQueue.size() + "," +
            "\"dataAge\":{\"bank\":" + lastBankUpdate + ",\"inventory\":" + lastInventoryUpdate +
            ",\"equipment\":" + lastEquipmentUpdate + ",\"ge\":" + lastGeUpdate + "}," +
            "\"eieVersion\":7," +
            "\"eieCapabilities\":[\"loot\",\"kills\",\"xp\",\"hitsplat\",\"animation\"," +
            "\"ammo\",\"ground_items\",\"actor_death\",\"chat_events\",\"ge_trade\"," +
            "\"levels\",\"spec\",\"rune_pouch\"]";
    }

    private String buildHealthJson()
    {
        return "{" + buildHealthInner() + "}";
    }

    private String buildBankInner()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("\"playerName\":\"").append(escapeJson(playerName)).append("\"");
        sb.append(",\"timestamp\":").append(lastBankUpdate);
        sb.append(",\"itemCount\":").append(bankItems.size());
        sb.append(",\"items\":{");
        boolean first = true;
        for (Map.Entry<Integer, Integer> entry : bankItems.entrySet())
        {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private String buildInventoryInner()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("\"timestamp\":").append(lastInventoryUpdate);
        sb.append(",\"usedSlots\":").append(inventoryItems.size());
        sb.append(",\"items\":{");
        boolean first = true;
        for (Map.Entry<Integer, Integer> entry : inventoryItems.entrySet())
        {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private String buildEquipmentInner()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("\"timestamp\":").append(lastEquipmentUpdate);
        sb.append(",\"slots\":{");
        boolean first = true;
        for (Map.Entry<String, int[]> entry : equipmentSlots.entrySet())
        {
            if (!first) sb.append(",");
            int[] data = entry.getValue();
            sb.append("\"").append(entry.getKey()).append("\":");
            sb.append("{\"id\":").append(data[0]).append(",\"qty\":").append(data[1]).append("}");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private String buildGeInner()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("\"playerName\":\"").append(escapeJson(playerName)).append("\",");
        sb.append("\"timestamp\":").append(lastGeUpdate).append(",\"offers\":[");
        int activeCount = 0;

        for (int i = 0; i < 8; i++)
        {
            if (i > 0) sb.append(",");
            OfferSnapshot offer = geOffers[i];

            if (offer == null || "EMPTY".equals(offer.state) || offer.itemId <= 0)
            {
                sb.append("{\"slot\":").append(i).append(",\"state\":\"EMPTY\",\"itemId\":null}");
            }
            else
            {
                activeCount++;
                Integer avgFillPrice = offer.quantityFilled > 0 ? (int)(offer.spent / offer.quantityFilled) : null;
                double fillPercent = offer.totalQuantity > 0 ? (offer.quantityFilled * 100.0) / offer.totalQuantity : 0;

                sb.append("{\"slot\":").append(i);
                sb.append(",\"state\":\"").append(offer.state).append("\"");
                sb.append(",\"itemId\":").append(offer.itemId);
                sb.append(",\"price\":").append(offer.price);
                sb.append(",\"totalQuantity\":").append(offer.totalQuantity);
                sb.append(",\"quantityFilled\":").append(offer.quantityFilled);
                sb.append(",\"spent\":").append(offer.spent);
                sb.append(",\"avgFillPrice\":").append(avgFillPrice != null ? avgFillPrice : "null");
                sb.append(",\"fillPercent\":").append(String.format("%.1f", fillPercent));
                sb.append("}");
            }
        }

        sb.append("],\"activeSlots\":").append(activeCount);
        sb.append(",\"emptySlots\":").append(8 - activeCount);
        return sb.toString();
    }

    private String buildAllDataInner()
    {
        // NOTE: Do NOT call client.getItemContainer() here — this method is called from
        // the WebSocket thread (onText/handleRelayMessage), not the game client thread.
        // Direct client reads are done in onGameStateChanged(LOGGED_IN) instead.
        return "\"health\":{" + buildHealthInner() + "}," +
            "\"bank\":{" + buildBankInner() + "}," +
            "\"inventory\":{" + buildInventoryInner() + "}," +
            "\"equipment\":{" + buildEquipmentInner() + "}," +
            "\"ge\":{" + buildGeInner() + "}," +
            "\"timestamp\":" + System.currentTimeMillis();
    }

    private static String escapeJson(String s)
    {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ================================================================
    // F4 — NPC LOOT RECEIVED
    // ================================================================

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event)
    {
        if (!loggedIn) return;
        NPC npc = event.getNpc();
        if (npc == null) return;

        String npcName   = npc.getName() != null ? npc.getName() : "";
        int    npcId     = npc.getId();
        int    npcLevel  = npc.getCombatLevel();
        long   ts        = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder();
        sb.append("\"type\":\"npc_loot\"");
        sb.append(",\"npcName\":\"").append(escapeJson(npcName)).append("\"");
        sb.append(",\"npcId\":").append(npcId);
        sb.append(",\"npcLevel\":").append(npcLevel);
        sb.append(",\"timestamp\":").append(ts);

        long totalValue = 0;
        sb.append(",\"items\":[");
        boolean first = true;
        for (net.runelite.client.game.ItemStack stack : event.getItems())
        {
            int rawId  = stack.getId();
            int realId = itemManager.canonicalize(rawId);
            int qty    = stack.getQuantity();
            long price = (long) itemManager.getItemPrice(realId) * qty;
            totalValue += price;

            if (!first) sb.append(",");
            sb.append("{\"id\":").append(realId)
              .append(",\"qty\":").append(qty)
              .append(",\"price\":").append(price).append("}");
            first = false;
        }
        sb.append("],\"totalValue\":").append(totalValue);

        queueEieEvent(sb.toString());
    }

    // ================================================================
    // F5 — ITEM SPAWNED / ITEM DESPAWNED
    // ================================================================

    @Subscribe
    public void onItemSpawned(ItemSpawned event)
    {
        if (!loggedIn) return;
        net.runelite.api.Tile tile = event.getTile();
        net.runelite.api.TileItem tileItem = event.getItem();
        if (tile == null || tileItem == null) return;

        WorldPoint wp = tile.getWorldLocation();
        if (wp == null) return;

        // Only track self-ownership items (ownership 0=everyone, 1=self)
        // TileItem.getOwnership() returns 0 (unknown/public) or 1 (self)
        if (tileItem.getOwnership() != 1 && tileItem.getOwnership() != 0) return;

        int rawId  = tileItem.getId();
        int realId = itemManager.canonicalize(rawId);
        int qty    = tileItem.getQuantity();

        long key = GroundItemData.tileKey(wp.getPlane(), wp.getX(), wp.getY(), realId);
        groundItems.put(key, new GroundItemData(realId, qty, wp.getPlane(),
            wp.getX(), wp.getY(), System.currentTimeMillis(), currentGameTick));
        // No broadcast on spawn — wait for despawn/pickup confirmation
    }

    @Subscribe
    public void onItemDespawned(ItemDespawned event)
    {
        if (!loggedIn) return;
        net.runelite.api.Tile tile = event.getTile();
        net.runelite.api.TileItem tileItem = event.getItem();
        if (tile == null || tileItem == null) return;

        WorldPoint wp = tile.getWorldLocation();
        if (wp == null) return;

        int rawId  = tileItem.getId();
        int realId = itemManager.canonicalize(rawId);

        long key = GroundItemData.tileKey(wp.getPlane(), wp.getX(), wp.getY(), realId);
        GroundItemData data = groundItems.remove(key);
        Integer pickedUpId = pendingPickups.remove(key);

        // If player clicked "Take" on this item → it is a pickup loot event
        if (pickedUpId != null && data != null)
        {
            int qty = data.quantity;
            long price = (long) itemManager.getItemPrice(realId) * qty;
            queueEieEvent("\"type\":\"ground_pickup\"" +
                ",\"itemId\":" + realId +
                ",\"qty\":" + qty +
                ",\"price\":" + price +
                ",\"timestamp\":" + System.currentTimeMillis());
        }
        else if (data == null)
        {
            // Item we weren't tracking → loot_despawned (not picked up by us)
            queueEieEvent("\"type\":\"loot_despawned\"" +
                ",\"itemId\":" + realId +
                ",\"timestamp\":" + System.currentTimeMillis());
        }
    }

    // ================================================================
    // F6 — ACTOR DEATH
    // ================================================================

    @Subscribe
    public void onActorDeath(ActorDeath event)
    {
        if (!loggedIn) return;
        Actor actor = event.getActor();
        if (actor == null) return;

        Player local = client.getLocalPlayer();
        long ts = System.currentTimeMillis();

        if (actor == local)
        {
            // Player died — snapshot equipment + location context
            StringBuilder sb = new StringBuilder();
            sb.append("\"type\":\"player_death\"");
            sb.append(",\"timestamp\":").append(ts);
            sb.append(",\"regionId\":").append(lastRegionId);

            // Wilderness detection: check if player is in wilderness via Varbits
            boolean inWilderness = false;
            try {
                int wildyLevel = client.getVarbitValue(5963); // WILDERNESS_LEVEL varbit
                inWilderness = wildyLevel > 0;
                sb.append(",\"wildernessLevel\":").append(wildyLevel);
            } catch (Exception ignored) {}
            sb.append(",\"inWilderness\":").append(inWilderness);

            // PvP world detection
            boolean isPvpWorld = false;
            try {
                int worldType = client.getWorldType().stream()
                    .mapToInt(Enum::ordinal).sum();
                isPvpWorld = client.getWorldType().contains(net.runelite.api.WorldType.PVP);
            } catch (Exception ignored) {}
            sb.append(",\"isPvpWorld\":").append(isPvpWorld);

            sb.append(",\"equipment\":{");
            boolean first = true;
            for (Map.Entry<String, int[]> e : equipmentSlots.entrySet())
            {
                if (!first) sb.append(",");
                int[] d = e.getValue();
                sb.append("\"").append(e.getKey()).append("\":");
                sb.append("{\"id\":").append(d[0]).append(",\"qty\":").append(d[1]).append("}");
                first = false;
            }
            sb.append("}");
            queueEieEvent(sb.toString());

            // Reset combat state
            inCombat = false;
            combatTickCount = 0;
        }
        else if (actor instanceof NPC && inCombat)
        {
            // NPC death — heuristic: within 15 tiles and we're in combat
            if (local != null)
            {
                WorldPoint playerWp = local.getWorldLocation();
                WorldPoint npcWp    = actor.getWorldLocation();
                if (playerWp != null && npcWp != null &&
                    playerWp.distanceTo(npcWp) <= 15)
                {
                    NPC npc = (NPC) actor;
                    String npcName = npc.getName() != null ? npc.getName() : "";
                    queueEieEvent("\"type\":\"npc_death\"" +
                        ",\"npcName\":\"" + escapeJson(npcName) + "\"" +
                        ",\"npcId\":" + npc.getId() +
                        ",\"npcLevel\":" + npc.getCombatLevel() +
                        ",\"timestamp\":" + ts);
                }
            }
        }
    }

    // ================================================================
    // F7 — STAT CHANGED (XP batching)
    // ================================================================

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        if (!loggedIn) return;
        Skill skill = event.getSkill();
        if (skill == null) return;

        String skillName = skill.getName();
        int    newXp     = event.getXp();
        Integer prevXpVal = previousXp.put(skillName, newXp);

        if (prevXpVal == null) return; // first observation, no delta yet

        int delta = newXp - prevXpVal;
        if (delta <= 0 || delta > 200_000_000) return; // sanity check

        // Accumulate into batch window
        int[] state = xpAccumulator.computeIfAbsent(skillName, k -> new int[]{0, XP_BATCH_WINDOW});
        state[0] += delta;
        state[1] = XP_BATCH_WINDOW; // reset timer on each new gain
    }

    // ================================================================
    // F8 — CHAT MESSAGE (boss kills, PBs, collection log, slayer)
    // ================================================================

    private static final Pattern KC_PATTERN =
        Pattern.compile("Your (?<boss>.+?) kill count is: (?<kc>\\d+)\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern PB_PATTERN =
        Pattern.compile("(?:Fight duration|New personal best): (?<time>[\\d:]+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOG_PATTERN =
        Pattern.compile("New item added to your collection log: (?<item>.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SLAYER_ASSIGN_PATTERN =
        Pattern.compile("(?:Your new task is to kill|You are now assigned to kill) (?<qty>\\d+) (?<task>.+?)(?: for \\d+ Slayer XP)?\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern SLAYER_COMPLETE_PATTERN =
        Pattern.compile("You have completed your task! You killed (?<qty>\\d+) (?<task>.+?)\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUABLE_DROP_PATTERN =
        Pattern.compile("Valuable drop: (?<qty>[\\d,]+) x (?<item>.+?) \\((?<value>[\\d,]+) coins\\)", Pattern.CASE_INSENSITIVE);

    // Bug 14B: Blowpipe check — dart type detection from in-game "Check" command
    // OSRS format: "Darts: Adamant dart x 567. Scales: 1,234/16,383."
    // Pattern matches the blowpipe check output format
    private static final Pattern BLOWPIPE_CHECK_PATTERN =
        Pattern.compile("(?:Darts?|darts?):?\\s*(?<dart>[A-Z][a-z]+ dart)\\s*x\\s*(?<qty>[\\d,]+)", Pattern.CASE_INSENSITIVE);

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (!loggedIn) return;
        ChatMessageType type = event.getType();
        if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM) return;

        String msg = event.getMessage();
        if (msg == null || msg.isEmpty()) return;
        // Strip HTML tags RuneLite may inject
        msg = msg.replaceAll("<[^>]+>", "").trim();

        long ts = System.currentTimeMillis();
        Matcher m;

        m = KC_PATTERN.matcher(msg);
        if (m.find())
        {
            queueEieEvent("\"type\":\"kill_count\"" +
                ",\"source\":\"" + escapeJson(m.group("boss")) + "\"" +
                ",\"kc\":" + m.group("kc") +
                ",\"timestamp\":" + ts);
            return;
        }

        m = PB_PATTERN.matcher(msg);
        if (m.find())
        {
            queueEieEvent("\"type\":\"personal_best\"" +
                ",\"time\":\"" + escapeJson(m.group("time")) + "\"" +
                ",\"timestamp\":" + ts);
            return;
        }

        m = CLOG_PATTERN.matcher(msg);
        if (m.find())
        {
            queueEieEvent("\"type\":\"collection_log\"" +
                ",\"item\":\"" + escapeJson(m.group("item")) + "\"" +
                ",\"timestamp\":" + ts);
            return;
        }

        m = SLAYER_ASSIGN_PATTERN.matcher(msg);
        if (m.find())
        {
            queueEieEvent("\"type\":\"slayer_task\"" +
                ",\"task\":\"" + escapeJson(m.group("task")) + "\"" +
                ",\"qty\":" + m.group("qty").replaceAll(",", "") +
                ",\"assigned\":true" +
                ",\"timestamp\":" + ts);
            return;
        }

        m = SLAYER_COMPLETE_PATTERN.matcher(msg);
        if (m.find())
        {
            queueEieEvent("\"type\":\"slayer_complete\"" +
                ",\"task\":\"" + escapeJson(m.group("task")) + "\"" +
                ",\"qty\":" + m.group("qty").replaceAll(",", "") +
                ",\"timestamp\":" + ts);
            return;
        }

        m = VALUABLE_DROP_PATTERN.matcher(msg);
        if (m.find())
        {
            String valueStr = m.group("value").replaceAll(",", "");
            queueEieEvent("\"type\":\"valuable_drop\"" +
                ",\"item\":\"" + escapeJson(m.group("item")) + "\"" +
                ",\"qty\":" + m.group("qty").replaceAll(",", "") +
                ",\"value\":" + valueStr +
                ",\"timestamp\":" + ts);
            return;
        }

        // Bug 14B: Blowpipe check — dart type from "Check" command
        m = BLOWPIPE_CHECK_PATTERN.matcher(msg);
        if (m.find())
        {
            queueEieEvent("\"type\":\"weapon_check\"" +
                ",\"weapon\":\"blowpipe\"" +
                ",\"dartName\":\"" + escapeJson(m.group("dart")) + "\"" +
                ",\"dartQty\":" + m.group("qty").replaceAll(",", "") +
                ",\"timestamp\":" + ts);
            return;
        }

        // Always forward raw chat message for JS-side pattern matching
        // (charge messages, degradation warnings, etc. that we don't parse here)
        queueEieEvent("\"type\":\"chat_message\"" +
            ",\"message\":\"" + escapeJson(msg) + "\"" +
            ",\"timestamp\":" + ts);
    }

    // ================================================================
    // F2 — ANIMATION CHANGED + HITSPLAT APPLIED
    // ================================================================

    @Subscribe
    public void onAnimationChanged(AnimationChanged event)
    {
        if (!loggedIn) return;
        Actor actor = event.getActor();
        Player local = client.getLocalPlayer();
        if (actor == null || actor != local) return;

        int animId = actor.getAnimation();
        if (animId < 0) return;
        // Deduplicate: only emit once per game tick
        if (currentGameTick == lastAnimTick) return;
        lastAnimTick = currentGameTick;

        inCombat = true;
        combatTickCount = 0;

        // Include equipped weapon and ammo for ChargeTracker/AmmoTracker
        StringBuilder sb = new StringBuilder();
        sb.append("\"type\":\"animation\",\"animationId\":").append(animId);

        int[] weapon = equipmentSlots.get("WEAPON");
        if (weapon != null) {
            sb.append(",\"weaponId\":").append(weapon[0]);
        }
        int[] ammo = equipmentSlots.get("AMMO");
        if (ammo != null) {
            sb.append(",\"ammoId\":").append(ammo[0]);
            sb.append(",\"ammoQty\":").append(ammo[1]);
        }

        sb.append(",\"timestamp\":").append(System.currentTimeMillis());
        queueEieEvent(sb.toString());
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event)
    {
        if (!loggedIn) return;
        Actor target = event.getActor();
        if (target == null) return;

        Player local = client.getLocalPlayer();
        Hitsplat hitsplat = event.getHitsplat();
        if (hitsplat == null) return;

        boolean isOutgoing = hitsplat.isMine();
        boolean isIncoming = (target == local) && !isOutgoing;
        if (!isOutgoing && !isIncoming) return;

        if (isOutgoing)
        {
            inCombat = true;
            combatTickCount = 0;
        }

        String targetName = "";
        boolean targetIsNpc = target instanceof NPC;
        if (targetIsNpc) targetName = escapeJson(((NPC) target).getName());
        else if (target instanceof Player) targetName = escapeJson(((Player) target).getName());

        queueEieEvent("\"type\":\"hitsplat\"" +
            ",\"direction\":\"" + (isOutgoing ? "out" : "in") + "\"" +
            ",\"amount\":" + hitsplat.getAmount() +
            ",\"targetName\":\"" + targetName + "\"" +
            ",\"targetIsNpc\":" + targetIsNpc +
            ",\"timestamp\":" + System.currentTimeMillis());
    }

    // ================================================================
    // F1 — MENU OPTION CLICKED (item consumption / pickup detection)
    // ================================================================

    private static final Set<String> CONSUME_ACTIONS = new HashSet<>(Arrays.asList(
        "Eat", "Drink", "Bury", "Scatter"
    ));
    private static final Set<String> DESTROY_ACTIONS = new HashSet<>(Arrays.asList(
        "Destroy", "Drop", "Release", "Empty"
    ));
    // Thrown weapons held in WEAPON slot (not AMMO slot) — need explicit tracking
    private static final Set<Integer> THROWN_WEAPON_IDS = new HashSet<>(Arrays.asList(
        11959, // Black chinchompa
        11957, // Red chinchompa
        9976,  // Chinchompa (grey)
        10034, // Crystal chinchompa (unused but future-proof)
        825    // Toktz-xil-ul (obsidian throwing ring)
    ));

    /**
     * Categorise a menu action into an EIE-meaningful string, or null if irrelevant.
     */
    private String categorizeAction(String option)
    {
        if (option == null) return null;
        if (CONSUME_ACTIONS.contains(option)) return "consume";
        if (DESTROY_ACTIONS.contains(option)) return "destroy";
        if ("Use".equals(option))             return "transform"; // item-on-item, not consumption
        if ("Take".equals(option))            return "take";
        return null;
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (!loggedIn) return;

        String option   = event.getMenuOption();
        MenuAction mact = event.getMenuAction();

        // ── Game object interactions (POH furniture, altars, portals, etc.) ──
        // Emit as "action" events with target + option for investment savings tracking
        boolean isGameObject = (
            mact == MenuAction.GAME_OBJECT_FIRST_OPTION ||
            mact == MenuAction.GAME_OBJECT_SECOND_OPTION ||
            mact == MenuAction.GAME_OBJECT_THIRD_OPTION ||
            mact == MenuAction.GAME_OBJECT_FOURTH_OPTION ||
            mact == MenuAction.GAME_OBJECT_FIFTH_OPTION
        );
        if (isGameObject)
        {
            String target = event.getMenuTarget();
            if (target != null) target = target.replaceAll("<[^>]*>", "").trim(); // strip color tags
            if (target != null && !target.isEmpty())
            {
                long ts = System.currentTimeMillis();
                StringBuilder sb = new StringBuilder();
                sb.append("\"type\":\"action\"");
                sb.append(",\"target\":\"").append(escapeJson(target)).append("\"");
                sb.append(",\"option\":\"").append(escapeJson(option != null ? option : "")).append("\"");
                sb.append(",\"objectId\":").append(event.getId());
                sb.append(",\"timestamp\":").append(ts);
                queueEieEvent(sb.toString());
            }
            return;
        }

        // Accept inventory widget clicks (Eat, Drink, etc.) and direct item/ground clicks
        boolean isWidgetClick = (mact == MenuAction.CC_OP || mact == MenuAction.CC_OP_LOW_PRIORITY);
        if (!isWidgetClick &&
            mact != MenuAction.ITEM_FIRST_OPTION &&
            mact != MenuAction.ITEM_SECOND_OPTION &&
            mact != MenuAction.ITEM_THIRD_OPTION &&
            mact != MenuAction.ITEM_FOURTH_OPTION &&
            mact != MenuAction.ITEM_FIFTH_OPTION &&
            mact != MenuAction.GROUND_ITEM_FIRST_OPTION &&
            mact != MenuAction.GROUND_ITEM_SECOND_OPTION &&
            mact != MenuAction.GROUND_ITEM_THIRD_OPTION &&
            mact != MenuAction.GROUND_ITEM_FOURTH_OPTION &&
            mact != MenuAction.GROUND_ITEM_FIFTH_OPTION)
        {
            return;
        }

        String category = categorizeAction(option);
        if (category == null) return;

        // Prefer getItemId() (correct for both inventory widget and ground item clicks)
        int rawId = event.getItemId();
        if (rawId <= 0) rawId = event.getId(); // fallback for ground items
        int itemId = rawId > 0 ? itemManager.canonicalize(rawId) : rawId;

        // getItemOp() is the menu operation index (1-5), NOT a quantity
        // For consumption/destruction actions, qty is always 1
        int qty = 1;

        if ("take".equals(category))
        {
            // Ground item "Take" — use scene coords from event to find the correct tile
            // (player clicks and WALKS to item; player's current tile may be different)
            try
            {
                int sceneX = event.getParam0();
                int sceneY = event.getParam1();
                WorldPoint wp = WorldPoint.fromScene(client, sceneX, sceneY, client.getPlane());
                long key = GroundItemData.tileKey(wp.getPlane(), wp.getX(), wp.getY(), itemId);
                pendingPickups.put(key, itemId);
            }
            catch (Exception e)
            {
                // Fallback to player position if scene coords fail
                Player lp = client.getLocalPlayer();
                if (lp != null)
                {
                    WorldPoint wp = lp.getWorldLocation();
                    long key = GroundItemData.tileKey(wp.getPlane(), wp.getX(), wp.getY(), itemId);
                    pendingPickups.put(key, itemId);
                }
            }
            return; // event fires via onItemDespawned
        }

        // consume / destroy → emit immediately with resolved item name
        String itemName = "";
        try
        {
            ItemComposition comp = itemManager.getItemComposition(itemId > 0 ? itemId : rawId);
            if (comp != null) itemName = comp.getName();
        }
        catch (Exception e) { /* ignore — name is optional */ }

        long ts = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("\"type\":\"item_used\"");
        sb.append(",\"action\":\"").append(category).append("\"");
        sb.append(",\"itemId\":").append(itemId);
        sb.append(",\"itemName\":\"").append(escapeJson(itemName != null ? itemName : "")).append("\"");
        sb.append(",\"qty\":").append(qty);
        sb.append(",\"timestamp\":").append(ts);
        queueEieEvent(sb.toString());
    }

    // ================================================================
    // EIE EVENT PIPELINE
    // ================================================================

    private static boolean arraysEqual(int[] a, int[] b)
    {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a[0] == b[0] && a[1] == b[1];
    }

    /**
     * Queue a single EIE JSON event object (without outer braces) for the
     * current game tick. Thread-safe — can be called from any event handler.
     */
    private void queueEieEvent(String eventJson)
    {
        // Inject playerName into every event for account attribution
        String withPlayer = eventJson + ",\"playerName\":\"" + escapeJson(playerName) + "\"";
        tickBuffer.add(withPlayer);
    }


    // ================================================================
    // GROUND ITEM DATA (inner class)
    // ================================================================

    /** Lightweight record of a ground item we are tracking. */
    private static class GroundItemData
    {
        final int itemId;
        final int quantity;
        final int plane;
        final int worldX;
        final int worldY;
        final long spawnedAt; // System.currentTimeMillis()
        final int spawnedTick;

        GroundItemData(int itemId, int quantity, int plane, int worldX, int worldY,
                       long spawnedAt, int spawnedTick)
        {
            this.itemId = itemId;
            this.quantity = quantity;
            this.plane = plane;
            this.worldX = worldX;
            this.worldY = worldY;
            this.spawnedAt = spawnedAt;
            this.spawnedTick = spawnedTick;
        }

        /** Pack tile coordinates into a single long key.
         *  2 bits plane | 18 bits worldX | 15 bits worldY | 15 bits itemId = 50 bits.
         *  15 bits = max 32767, covers all OSRS item IDs (~28000). */
        static long tileKey(int plane, int worldX, int worldY, int itemId)
        {
            return ((long) (plane & 0x3) << 48) | ((long) (worldX & 0x3FFFF) << 30) |
                   ((long) (worldY & 0x7FFF) << 15) | (itemId & 0x7FFF);
        }
    }
}
