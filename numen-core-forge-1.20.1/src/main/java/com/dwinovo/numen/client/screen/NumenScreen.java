package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.llm.LlmEndpointDiagnostics;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.http.LlmHttpException;
import com.dwinovo.numen.agent.model.ModelRegistry;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.AgentContextPolicy;
import com.dwinovo.numen.client.agent.ClientDeaths;
import com.dwinovo.numen.client.agent.CompanionAiConfigStore;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.LongTermMemory;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.data.ClientTaskList;
import com.dwinovo.numen.client.data.ClientCompanionSettings;
import com.dwinovo.numen.client.diagnostic.AiFailureReporter;
import com.dwinovo.numen.network.payload.DismissRequestPayload;
import com.dwinovo.numen.network.payload.SummonRequestPayload;
import com.dwinovo.numen.network.payload.TaskUiRequestPayload;
import com.dwinovo.numen.network.payload.CompanionSettingsRequestPayload;
import com.dwinovo.numen.network.payload.OpenCompanionInventoryPayload;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.platform.services.INumenConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;

/**
 * The G-key companion workspace.  The visual layer deliberately uses vanilla
 * screen widgets and the vanilla player-inventory texture; the old themed
 * workspace, custom tab rail and item grid are no longer part of this screen.
 *
 * <p>The three pages are intentionally narrow in scope:</p>
 * <ul>
 *   <li>Chat keeps the complete persistent transcript, queued prompts, tool
 *       progress, task plan, interrupt and compaction controls.</li>
 *   <li>AI Settings uses the same buttons, cycle controls and edit boxes as
 *       Minecraft's Options screens.</li>
 *   <li>Inventory mirrors the companion's real InventoryMenu beside the owner's
 *       backpack. Moves and 2x2 crafting are executed authoritatively by the server.</li>
 * </ul>
 */
public final class NumenScreen extends Screen {

    private enum Page {
        CHAT("对话"), TASKS("任务"), SETTINGS("AI 设置"), DATA("AI 数据"), INVENTORY("背包");

        private final String label;

        Page(String label) {
            this.label = label;
        }
    }

    private static final int MAX_PROMPT = 1024;
    private static final int LINE_H = 10;
    private static final int TOOL_ARG_CHARS = 52;
    private static final int INVENTORY_REFRESH_TICKS = 20;

    private static final int WHITE = 0xFFFFFF;
    private static final int MUTED = 0xA0A0A0;
    private static final int FAINT = 0x707070;
    private static final int USER = 0x55FFFF;
    private static final int ASSISTANT = 0x55FF55;
    private static final int TOOL = 0xAAAAAA;
    private static final int OK = 0x55FF55;
    private static final int RUNNING = 0xFFFF55;
    private static final int FAILED = 0xFF5555;

    private static final String CUSTOM_MODEL = "__custom_model__";
    private static final String CUSTOM_COMPACT_LIMIT = "__custom_compact_limit__";
    private static final List<String> COMPACT_LIMIT_PRESETS =
            List.of("64000", "128000", "256000", "512000", CUSTOM_COMPACT_LIMIT);
    private static final String[] SPINNER = {"|", "/", "-", "\\"};

    private enum DiagnosticKind { CONNECTION, MODELS, CAPABILITIES }

    private final Screen parent;
    private UUID uuid;
    private String name;
    private Page page;

    private EditBox chatInput;
    private Button sendButton;
    private Button stopButton;
    private Button compactButton;
    private String savedInput = "";

    private boolean summoning;
    private EditBox summonInput;
    private UUID dismissPending;

    private boolean settingsLoaded;
    private boolean customModel;
    private boolean addingSite;
    private boolean showApiKey;
    private String wProvider = "";
    private String wApiKey = "";
    private String wModel = "";
    private String wBaseUrl = "";
    private boolean wFullUrl;
    private String wProxy = "";
    private String wSystemPrompt = "";
    private String wAutoCompactTokens = Integer.toString(CompanionAiConfigStore.DEFAULT_AUTO_COMPACT_TOKENS);
    private String wCustomAutoCompactTokens = wAutoCompactTokens;
    private String wCompactLimitPreset = "64000";
    private String wSiteName = "";
    private EditBox apiKeyInput;
    private EditBox modelInput;
    private EditBox baseUrlInput;
    private EditBox proxyInput;
    private EditBox systemPromptInput;
    private EditBox autoCompactInput;
    private EditBox siteNameInput;
    private CycleButton<String> providerButton;
    private CycleButton<String> modelButton;
    private CycleButton<String> compactLimitButton;
    private Button connectionTestButton;
    private Button modelDetectionButton;
    private Button capabilityDetectionButton;
    private Button fullUrlButton;
    private CycleButton<String> reasoningButton;
    private CycleButton<Boolean> webSearchButton;
    private CycleButton<Boolean> lowQualityButton;
    private long savedFlashUntil;
    private long warningUntil;
    private boolean diagnosticRunning;
    private DiagnosticKind diagnosticKind;
    private String diagnosticMessage = "";
    private int diagnosticColor = MUTED;
    private List<String> detectedModels = List.of();
    private List<String> detectedReasoning = List.of();
    private String wReasoning = CompanionAiConfigStore.REASONING_AUTO;
    private boolean wWebSearch = true;
    private boolean wLowQuality;
    private int settingsScroll;
    private int settingsMaxScroll;

    private int settingsX;
    private int settingsY;
    private int settingsWidth;
    private int settingsGap;
    private int settingsLabelWidth;

    private int chatScroll;
    private int lastMaxScroll;
    private boolean pinChatBottom = true;
    private final Set<String> expandedToolGroups = new HashSet<>();

    private int tickCounter;
    private int rosterSignature;
    private int taskUiSignature;
    private long dataReceivedAt;
    private EditBox healthInput, damageInput, attackSpeedInput, speedInput, armorInput,
            toughnessInput, knockbackInput, luckInput, respawnInput;
    private CycleButton<String> gameModeButton;
    private CycleButton<Boolean> invulnerableButton;
    private String wGameMode = "survival", wHealth = "20", wDamage = "1", wAttackSpeed = "4",
            wSpeed = "0.1", wArmor = "0", wToughness = "0", wKnockback = "0", wLuck = "0", wRespawn = "30";
    private boolean wInvulnerable;

    private NumenScreen(UUID uuid, String name, Page page, Screen parent) {
        super(Component.literal("Numen"));
        this.uuid = uuid;
        this.name = name;
        this.page = page;
        this.parent = parent;
    }

    /** Open the workspace focused on one companion. */
    public static void open(UUID uuid, String name) {
        Minecraft.getInstance().setScreen(new NumenScreen(uuid, name, Page.CHAT, null));
    }

    public static void openPage(UUID uuid, String name, String pageName) {
        Page target = switch (pageName == null ? "" : pageName) {
            case "tasks" -> Page.TASKS;
            case "settings" -> Page.SETTINGS;
            case "data" -> Page.DATA;
            default -> Page.CHAT;
        };
        Minecraft.getInstance().setScreen(new NumenScreen(uuid, name, target, null));
    }

    /** G-key entry: use the first live companion, or allow one to be summoned. */
    public static void openWorkspace() {
        List<NumenRoster.Entry> entries = NumenRoster.instance().entries();
        if (entries.isEmpty()) {
            Minecraft.getInstance().setScreen(new NumenScreen(null, null, Page.CHAT, null));
            return;
        }
        NumenRoster.Entry first = entries.get(0);
        Minecraft.getInstance().setScreen(new NumenScreen(first.uuid(), first.name(), Page.CHAT, null));
    }

    /** Entry used by /numen settings; it opens the same new three-page workspace. */
    public static void openSettings(Screen parent) {
        List<NumenRoster.Entry> entries = NumenRoster.instance().entries();
        NumenRoster.Entry first = entries.isEmpty() ? null : entries.get(0);
        Minecraft.getInstance().setScreen(new NumenScreen(
                first == null ? null : first.uuid(),
                first == null ? null : first.name(),
                Page.SETTINGS,
                parent));
    }

    private EntityAgentLoop loop() {
        return AgentLoopRegistry.getOrCreate(uuid);
    }

    @Override
    protected void init() {
        if (chatInput != null) savedInput = chatInput.getValue();
        preserveSettingsFields();
        clearWidgets();
        chatInput = null;
        sendButton = stopButton = compactButton = null;
        summonInput = null;
        apiKeyInput = modelInput = baseUrlInput = proxyInput = systemPromptInput = autoCompactInput = siteNameInput = null;
        providerButton = modelButton = null;
        compactLimitButton = null;
        connectionTestButton = modelDetectionButton = capabilityDetectionButton = null;
        reasoningButton = null; webSearchButton = null; lowQualityButton = null;
        healthInput = damageInput = attackSpeedInput = speedInput = armorInput = toughnessInput = knockbackInput = luckInput = respawnInput = null;
        gameModeButton = null; invulnerableButton = null;

        buildPageButtons();
        if (summoning) {
            buildSummonDialog();
        } else if (dismissPending != null) {
            buildDismissDialog();
        } else {
            buildCompanionSelector();
            switch (page) {
                case CHAT -> buildChatControls();
                case TASKS -> buildTaskControls();
                case SETTINGS -> buildSettingsControls();
                case DATA -> buildDataControls();
                case INVENTORY -> { }
            }
        }
        rosterSignature = rosterSignature();
        taskUiSignature = taskUiSignature();
        ClientCompanionSettings.Snapshot settingsSnapshot = uuid == null ? null : ClientCompanionSettings.get(uuid);
        dataReceivedAt = settingsSnapshot == null ? 0 : settingsSnapshot.receivedAt();
    }

    private void rebuild() {
        init();
    }

    private void buildPageButtons() {
        int total = Math.min(460, width - 20);
        int x = (width - total) / 2;
        int each = total / Page.values().length;
        int y = 25 - (page == Page.SETTINGS ? settingsScroll : 0);
        for (int i = 0; i < Page.values().length; i++) {
            Page target = Page.values()[i];
            int w = i == Page.values().length - 1 ? total - each * i : each;
            Button button = Button.builder(Component.literal(target.label), b -> selectPage(target))
                    .bounds(x + each * i, y, w, 20)
                    .build();
            button.active = target != page;
            addRenderableWidget(button);
        }
    }

    private void selectPage(Page target) {
        if (target == page) return;
        if (target == Page.INVENTORY && uuid != null) {
            Services.NETWORK.sendToServer(OpenCompanionInventoryPayload.ID,
                    new OpenCompanionInventoryPayload(uuid));
            return;
        }
        page = target;
        if (target != Page.SETTINGS) settingsScroll = 0;
        chatScroll = 0;
        pinChatBottom = true;
        if (target == Page.SETTINGS && !settingsLoaded) loadSettingsState();
        rebuild();
        if (target == Page.TASKS) requestTasks();
        if (target == Page.DATA) requestCompanionSettings();
    }

    private void buildCompanionSelector() {
        List<NumenRoster.Entry> entries = NumenRoster.instance().entries();
        if (!entries.isEmpty() && entries.stream().noneMatch(e -> e.uuid().equals(uuid))) {
            uuid = entries.get(0).uuid();
            name = entries.get(0).name();
        }

        int total = Math.min(440, width - 24);
        int x = (width - total) / 2;
        int y = 50 - (page == Page.SETTINGS ? settingsScroll : 0);
        int summonW = 58;
        int deleteW = entries.isEmpty() ? 0 : 58;
        int navW = entries.size() > 1 ? 24 : 0;
        int selectorW = total - summonW - deleteW - navW * 2 - (deleteW == 0 ? 4 : 8) - (navW == 0 ? 0 : 8);

        if (entries.isEmpty()) {
            Button empty = Button.builder(Component.literal("没有 AI 同伴"), b -> { })
                    .bounds(x, y, selectorW, 20).build();
            empty.active = false;
            addRenderableWidget(empty);
        } else {
            List<UUID> ids = entries.stream().map(NumenRoster.Entry::uuid).toList();
            if (navW > 0) addRenderableWidget(Button.builder(Component.literal("<"), b -> switchCompanionRelative(-1)).bounds(x, y, navW, 20).build());
            int selectorX = x + (navW > 0 ? navW + 4 : 0);
            providerNameForCompanion(uuid);
            CycleButton<UUID> selector = CycleButton.<UUID>builder(
                            id -> Component.literal(providerNameForCompanion(id)))
                    .withValues(ids)
                    .withInitialValue(uuid)
                    .create(selectorX, y, selectorW, 20, Component.literal("同伴"),
                            (button, id) -> switchTo(id, providerNameForCompanion(id)));
            addRenderableWidget(selector);
            if (navW > 0) addRenderableWidget(Button.builder(Component.literal(">"), b -> switchCompanionRelative(1)).bounds(selectorX + selectorW + 4, y, navW, 20).build());
        }

        int summonX = x + selectorW + 4 + (navW > 0 ? navW * 2 + 8 : 0);
        addRenderableWidget(Button.builder(Component.literal("召唤"), b -> {
            summoning = true;
            rebuild();
        }).bounds(summonX, y, summonW, 20).build());
        if (deleteW > 0) {
            addRenderableWidget(Button.builder(Component.literal("删除"), b -> {
                dismissPending = uuid;
                rebuild();
            }).bounds(summonX + summonW + 4, y, deleteW, 20).build());
        }
    }

    private void switchCompanionRelative(int delta) {
        List<NumenRoster.Entry> entries=NumenRoster.instance().entries();if(entries.size()<2)return;
        int current=0;for(int i=0;i<entries.size();i++)if(entries.get(i).uuid().equals(uuid)){current=i;break;}
        NumenRoster.Entry next=entries.get(Math.floorMod(current+delta,entries.size()));switchTo(next.uuid(),next.name());
    }

    private void switchTo(UUID nextUuid, String nextName) {
        if (Objects.equals(uuid, nextUuid)) return;
        uuid = nextUuid;
        name = nextName;
        savedInput = "";
        chatInput = null;
        chatScroll = 0;
        pinChatBottom = true;
        expandedToolGroups.clear();
        settingsLoaded = false;
        settingsScroll = 0;
        clearDetectedModels();
        rebuild();
    }

    private String providerNameForCompanion(UUID id) {
        if (id == null) return "?";
        for (NumenRoster.Entry entry : NumenRoster.instance().entries()) {
            if (entry.uuid().equals(id)) return entry.name();
        }
        return id.toString().substring(0, 8);
    }

    private void buildSummonDialog() {
        int fieldW = Math.min(240, width - 40);
        int x = (width - fieldW) / 2;
        int y = Math.max(78, height / 2 - 20);
        summonInput = new EditBox(font, x, y, fieldW, 20, Component.literal("同伴名称"));
        summonInput.setMaxLength(SummonRequestPayload.MAX_NAME);
        summonInput.setHint(Component.literal("输入新同伴名称"));
        addRenderableWidget(summonInput);
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> {
            summoning = false;
            rebuild();
        }).bounds(x, y + 28, (fieldW - 4) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("召唤"), b -> doSummon())
                .bounds(x + (fieldW + 4) / 2, y + 28, (fieldW - 4) / 2, 20).build());
        setInitialFocus(summonInput);
    }

    private void doSummon() {
        String newName = summonInput == null ? "" : summonInput.getValue().trim();
        if (newName.isEmpty()) return;
        Services.NETWORK.sendToServer(SummonRequestPayload.ID, new SummonRequestPayload(newName));
        summoning = false;
        rebuild();
    }

    private void buildDismissDialog() {
        int w = Math.min(240, width - 40);
        int x = (width - w) / 2;
        int y = Math.max(92, height / 2 + 10);
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> {
            dismissPending = null;
            rebuild();
        }).bounds(x, y, (w - 4) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("确认删除"), b -> dismissCompanion())
                .bounds(x + (w + 4) / 2, y, (w - 4) / 2, 20).build());
    }

    private void dismissCompanion() {
        UUID target = dismissPending;
        if (target == null) return;
        Services.NETWORK.sendToServer(DismissRequestPayload.ID, new DismissRequestPayload(target));
        dismissPending = null;
        if (target.equals(uuid)) {
            NumenRoster.Entry next = NumenRoster.instance().entries().stream()
                    .filter(e -> !e.uuid().equals(target)).findFirst().orElse(null);
            uuid = next == null ? null : next.uuid();
            name = next == null ? null : next.name();
        }
        rebuild();
    }

    private void buildChatControls() {
        if (uuid == null) return;
        int margin = 10;
        int inputY = height - 28;
        int compactW = 58;
        int stopW = 48;
        int sendW = 48;
        int gaps = 12;
        int inputW = Math.max(80, width - margin * 2 - compactW - stopW - sendW - gaps);

        chatInput = new EditBox(font, margin, inputY, inputW, 20, Component.literal("消息"));
        chatInput.setMaxLength(MAX_PROMPT);
        chatInput.setHint(Component.literal("对 " + name + " 说些什么…"));
        if (!savedInput.isEmpty()) {
            chatInput.setValue(savedInput);
            savedInput = "";
        }
        addRenderableWidget(chatInput);

        int x = margin + inputW + 4;
        sendButton = addRenderableWidget(Button.builder(Component.literal("发送"), b -> onSend())
                .bounds(x, inputY, sendW, 20).build());
        x += sendW + 4;
        stopButton = addRenderableWidget(Button.builder(Component.literal("停止"), b -> loop().abort())
                .bounds(x, inputY, stopW, 20).build());
        x += stopW + 4;
        compactButton = addRenderableWidget(Button.builder(Component.literal("压缩"), b -> loop().requestCompact())
                .bounds(x, inputY, compactW, 20).build());
        stopButton.active = loop().canInterrupt();
        compactButton.active = loop().canCompact();
        setInitialFocus(chatInput);
    }

    private void buildTaskControls() {
        requestTasks();
        if (uuid == null) return;
        ClientTaskList.Snapshot snapshot = ClientTaskList.get(uuid);
        if (snapshot == null) return;
        int y = taskQueueContentY();
        for (ClientTaskList.Entry task : snapshot.tasks()) {
            if (y + 20 > height - 8) break;
            int cancelX = width - 62;
            int actionX = cancelX - 58;
            String action = task.paused() ? "继续" : "暂停";
            addRenderableWidget(Button.builder(Component.literal(action), b -> controlTask(
                            task.paused() ? TaskUiRequestPayload.Action.RESUME : TaskUiRequestPayload.Action.PAUSE,
                            task.toolCallId()))
                    .bounds(actionX, y, 54, 18).build());
            addRenderableWidget(Button.builder(Component.literal("取消"), b -> controlTask(
                            TaskUiRequestPayload.Action.CANCEL, task.toolCallId()))
                    .bounds(cancelX, y, 54, 18).build());
            y += 24;
        }
    }

    private void controlTask(TaskUiRequestPayload.Action action, String toolCallId) {
        if (uuid == null) return;
        Services.NETWORK.sendToServer(TaskUiRequestPayload.ID, new TaskUiRequestPayload(uuid, action, toolCallId));
    }

    private void requestTasks() {
        if (uuid != null && Minecraft.getInstance().getConnection() != null)
            Services.NETWORK.sendToServer(TaskUiRequestPayload.ID,
                    new TaskUiRequestPayload(uuid, TaskUiRequestPayload.Action.REFRESH, ""));
    }

    private void buildDataControls() {
        if (uuid == null) return;
        ClientCompanionSettings.Snapshot s = ClientCompanionSettings.get(uuid);
        if (s == null) requestCompanionSettings();
        if (s != null) {
            wGameMode=s.gameMode(); wHealth=Double.toString(s.maxHealth()); wDamage=Double.toString(s.attackDamage());
            wAttackSpeed=Double.toString(s.attackSpeed()); wSpeed=Double.toString(s.movementSpeed());
            wArmor=Double.toString(s.armor()); wToughness=Double.toString(s.armorToughness());
            wKnockback=Double.toString(s.knockbackResistance()); wLuck=Double.toString(s.luck());
            wInvulnerable=s.invulnerable(); wRespawn=Integer.toString(s.respawnSeconds());
        }
        int w=Math.min(430,width-24), x=(width-w)/2, y=79, gap=24;
        gameModeButton=CycleButton.<String>builder(Component::literal).withValues("survival","creative","adventure","spectator")
                .withInitialValue(wGameMode).create(x,y,(w-4)/2,20,Component.literal("游戏模式"),(b,v)->wGameMode=v);addRenderableWidget(gameModeButton);
        invulnerableButton=CycleButton.onOffBuilder(wInvulnerable).create(x+(w+4)/2,y,(w-4)/2,20,Component.literal("无敌"),(b,v)->wInvulnerable=v);addRenderableWidget(invulnerableButton);
        int fieldW=(w-4)/2, valueOffset=64, valueW=fieldW-valueOffset;
        healthInput=dataField(x,y+gap,fieldW,valueOffset,valueW,wHealth);
        damageInput=dataField(x+(w+4)/2,y+gap,fieldW,valueOffset,valueW,wDamage);
        attackSpeedInput=dataField(x,y+gap*2,fieldW,valueOffset,valueW,wAttackSpeed);
        speedInput=dataField(x+(w+4)/2,y+gap*2,fieldW,valueOffset,valueW,wSpeed);
        armorInput=dataField(x,y+gap*3,fieldW,valueOffset,valueW,wArmor);
        toughnessInput=dataField(x+(w+4)/2,y+gap*3,fieldW,valueOffset,valueW,wToughness);
        knockbackInput=dataField(x,y+gap*4,fieldW,valueOffset,valueW,wKnockback);
        luckInput=dataField(x+(w+4)/2,y+gap*4,fieldW,valueOffset,valueW,wLuck);
        respawnInput=dataField(x,y+gap*5,w,valueOffset,valueW,wRespawn);
        addRenderableWidget(Button.builder(Component.literal("保存此伙伴数据"),b->saveCompanionSettings()).bounds(x+(w+4)/2,y+gap*5,fieldW,20).build());
    }

    private EditBox dataField(int groupX,int y,int groupW,int valueOffset,int valueW,String value){return vanillaField(groupX+valueOffset,y,Math.max(48,valueW),16,value,"");}

    private void requestCompanionSettings() {
        if(uuid!=null&&Minecraft.getInstance().getConnection()!=null) Services.NETWORK.sendToServer(
                CompanionSettingsRequestPayload.ID,new CompanionSettingsRequestPayload(uuid,false,"survival",20,1,4,0.1,0,0,0,0,false,30));
    }

    private void saveCompanionSettings() {
        if(uuid==null)return;
        try {
            double hp=Double.parseDouble(healthInput.getValue()), damage=Double.parseDouble(damageInput.getValue()), attackSpeed=Double.parseDouble(attackSpeedInput.getValue());
            double speed=Double.parseDouble(speedInput.getValue()), armor=Double.parseDouble(armorInput.getValue()), toughness=Double.parseDouble(toughnessInput.getValue());
            double knockback=Double.parseDouble(knockbackInput.getValue()), luck=Double.parseDouble(luckInput.getValue()); int respawn=Integer.parseInt(respawnInput.getValue());
            Services.NETWORK.sendToServer(CompanionSettingsRequestPayload.ID,new CompanionSettingsRequestPayload(uuid,true,wGameMode,hp,damage,attackSpeed,speed,armor,toughness,knockback,luck,wInvulnerable,respawn));
        } catch (NumberFormatException ignored) { warningUntil=System.currentTimeMillis()+3000; }
    }

    private void onSend() {
        if (chatInput == null || uuid == null) return;
        String text = chatInput.getValue().trim();
        if (text.isEmpty()) return;
        if (!NumenLlmClient.isConfigured(uuid)) {
            warningUntil = System.currentTimeMillis() + 4000;
            return;
        }
        loop().submitPrompt(text);
        chatInput.setValue("");
        pinChatBottom = true;
    }

    private void loadSettingsState() {
        CompanionAiConfigStore.Profile cfg = CompanionAiConfigStore.get(uuid);
        wProvider = NumenScreenState.blankTo(cfg.provider(), "openai");
        wApiKey = NumenScreenState.nullToEmpty(cfg.apiKey());
        wModel = NumenScreenState.nullToEmpty(cfg.model());
        wBaseUrl = NumenScreenState.nullToEmpty(cfg.baseUrl());
        wFullUrl = cfg.fullUrl() && !wBaseUrl.isBlank();
        wProxy = NumenScreenState.nullToEmpty(cfg.proxy());
        wSystemPrompt = NumenScreenState.nullToEmpty(cfg.systemPrompt());
        wReasoning = cfg.reasoningEffort();
        wWebSearch = cfg.webSearchEnabled();
        wLowQuality = cfg.lowQualityAi();
        wAutoCompactTokens = Integer.toString(cfg.autoCompactTokens());
        wCompactLimitPreset = compactLimitPreset(cfg.autoCompactTokens());
        wCustomAutoCompactTokens = wAutoCompactTokens;
        ModelRegistry.Provider provider = ModelRegistry.provider(LlmProviders.normalize(wProvider));
        boolean known = provider != null && provider.models().stream().anyMatch(m -> m.id().equals(wModel));
        customModel = provider != null && (provider.custom() || (!wModel.isBlank() && !known));
        addingSite = false;
        settingsLoaded = true;
    }

    private void preserveSettingsFields() {
        if (apiKeyInput != null) wApiKey = apiKeyInput.getValue();
        if (modelInput != null) wModel = modelInput.getValue();
        if (baseUrlInput != null) wBaseUrl = baseUrlInput.getValue();
        if (proxyInput != null) wProxy = proxyInput.getValue();
        if (systemPromptInput != null) wSystemPrompt = systemPromptInput.getValue();
        if (autoCompactInput != null) {
            wAutoCompactTokens = autoCompactInput.getValue();
            if (CUSTOM_COMPACT_LIMIT.equals(wCompactLimitPreset)) {
                wCustomAutoCompactTokens = wAutoCompactTokens;
            }
        }
        if (siteNameInput != null) wSiteName = siteNameInput.getValue();
        if (providerButton != null) wProvider = providerButton.getValue();
        if (modelButton != null && !CUSTOM_MODEL.equals(modelButton.getValue())) wModel = modelButton.getValue();
        if (compactLimitButton != null) {
            wCompactLimitPreset = compactLimitButton.getValue();
            if (!CUSTOM_COMPACT_LIMIT.equals(wCompactLimitPreset)) wAutoCompactTokens = wCompactLimitPreset;
        }
        if (reasoningButton != null) wReasoning = reasoningButton.getValue();
        if (webSearchButton != null) wWebSearch = webSearchButton.getValue();
        if (lowQualityButton != null) wLowQuality = lowQualityButton.getValue();
    }

    private void buildSettingsControls() {
        if (uuid == null) return;
        if (!settingsLoaded) loadSettingsState();
        settingsWidth = Math.min(390, width - 30);
        settingsX = (width - settingsWidth) / 2;
        settingsY = 88 - settingsScroll;
        settingsGap = 25;
        settingsLabelWidth = Math.min(92, settingsWidth / 3);
        if (addingSite) {
            buildAddSiteControls();
            return;
        }

        List<String> providers = ModelRegistry.providers().stream().map(ModelRegistry.Provider::id).toList();
        if (providers.isEmpty()) providers = List.of("openai");
        if (!providers.contains(wProvider)) wProvider = providers.get(0);
        providerButton = CycleButton.<String>builder(id -> Component.literal(providerDisplayName(id)))
                .withValues(providers)
                .withInitialValue(wProvider)
                .create(settingsX, settingsY, settingsWidth, 20, Component.literal("提供商"),
                        (button, value) -> onProviderChanged(value));
        addRenderableWidget(providerButton);

        int modelY = settingsY + settingsGap;
        ModelRegistry.Provider provider = ModelRegistry.provider(LlmProviders.normalize(wProvider));
        List<String> knownModels = provider == null
                ? new ArrayList<>()
                : new ArrayList<>(provider.models().stream().map(ModelRegistry.Model::id).toList());
        for (String detected : detectedModels) {
            if (!knownModels.contains(detected)) knownModels.add(detected);
        }
        boolean providerRequiresCustom = provider != null && provider.custom();
        if (customModel || (providerRequiresCustom && detectedModels.isEmpty()) || knownModels.isEmpty()) {
            customModel = true;
            int backW = knownModels.isEmpty() ? 0 : 58;
            modelInput = vanillaField(settingsX + settingsLabelWidth, modelY,
                    settingsWidth - settingsLabelWidth - (backW == 0 ? 0 : backW + 4),
                    128, wModel, "模型 ID");
            if (backW > 0) {
                addRenderableWidget(Button.builder(Component.literal("预设"), b -> {
                    preserveSettingsFields();
                    customModel = false;
                    wModel = knownModels.get(0);
                    discardSettingsWidgets();
                    rebuild();
                }).bounds(settingsX + settingsWidth - backW, modelY, backW, 20).build());
            }
        } else {
            knownModels.add(CUSTOM_MODEL);
            String initial = knownModels.contains(wModel) ? wModel : knownModels.get(0);
            wModel = initial;
            modelButton = CycleButton.<String>builder(id -> Component.literal(
                            CUSTOM_MODEL.equals(id) ? "自定义…" : id))
                    .withValues(knownModels)
                    .withInitialValue(initial)
                    .create(settingsX, modelY, settingsWidth, 20, Component.literal(detectedModels.isEmpty()
                                    ? "模型" : "模型（检测 " + detectedModels.size() + "）"),
                            (button, value) -> onModelChanged(value));
            addRenderableWidget(modelButton);
        }

        int apiY = settingsY + settingsGap * 2;
        int revealW = 54;
        apiKeyInput = vanillaField(settingsX + settingsLabelWidth, apiY,
                settingsWidth - settingsLabelWidth - revealW - 4, 512, wApiKey, "API Key");
        apiKeyInput.setFormatter((text, index) -> FormattedCharSequence.forward(
                showApiKey ? text : "•".repeat(text.length()), Style.EMPTY));
        addRenderableWidget(Button.builder(Component.literal(showApiKey ? "隐藏" : "显示"), b -> {
            preserveSettingsFields();
            showApiKey = !showApiKey;
            rebuild();
        }).bounds(settingsX + settingsWidth - revealW, apiY, revealW, 20).build());

        int baseY = settingsY + settingsGap * 3;
        int fullUrlW = 82;
        baseUrlInput = vanillaField(settingsX + settingsLabelWidth, baseY,
                settingsWidth - settingsLabelWidth - fullUrlW - 4, 256, wBaseUrl,
                LlmProviders.byId(wProvider).defaultBaseUrl());
        fullUrlButton = addRenderableWidget(Button.builder(fullUrlLabel(), b -> toggleFullUrl())
                .bounds(settingsX + settingsWidth - fullUrlW, baseY, fullUrlW, 20).build());
        updateFullUrlButton();
        int proxyY = settingsY + settingsGap * 4;
        proxyInput = vanillaField(settingsX + settingsLabelWidth, proxyY,
                settingsWidth - settingsLabelWidth, 128, wProxy, "host:port（可选）");
        int promptY = settingsY + settingsGap * 5;
        systemPromptInput = vanillaField(settingsX + settingsLabelWidth, promptY,
                settingsWidth - settingsLabelWidth, 4096, wSystemPrompt, "额外系统提示（可选）");

        int reasoningY = settingsY + settingsGap * 6;
        List<String> reasoningValues = detectedReasoning.isEmpty()
                ? List.of("auto", "none", "minimal", "low", "medium", "high", "xhigh")
                : NumenScreenState.joinReasoningValues(detectedReasoning);
        if (!reasoningValues.contains(wReasoning)) wReasoning = "auto";
        reasoningButton = CycleButton.<String>builder(v -> Component.literal(reasoningLabel(v)))
                .withValues(reasoningValues).withInitialValue(wReasoning)
                .create(settingsX, reasoningY, settingsWidth, 20, Component.literal("思考强度"),
                        (button, value) -> wReasoning = value);
        addRenderableWidget(reasoningButton);

        int webY = settingsY + settingsGap * 7;
        webSearchButton = CycleButton.onOffBuilder(wWebSearch)
                .create(settingsX, webY, settingsWidth, 20, Component.literal("全网搜索（偏好 MC百科）"),
                        (button, value) -> wWebSearch = value);
        addRenderableWidget(webSearchButton);

        int lowQualityY = settingsY + settingsGap * 8;
        lowQualityButton = CycleButton.onOffBuilder(wLowQuality)
                .create(settingsX, lowQualityY, settingsWidth, 20,
                        Component.literal("低质 AI（使用超详细 Skill）"),
                        (button, value) -> wLowQuality = value);
        addRenderableWidget(lowQualityButton);

        int compactY = settingsY + settingsGap * 9;
        int presetWidth = Math.min(190, Math.max(145, settingsWidth / 2));
        compactLimitButton = CycleButton.<String>builder(NumenScreen::compactLimitLabel)
                .withValues(COMPACT_LIMIT_PRESETS).withInitialValue(wCompactLimitPreset)
                .create(settingsX, compactY, presetWidth, 20, Component.literal("压缩阈值"),
                        (button, value) -> {
                            if (CUSTOM_COMPACT_LIMIT.equals(wCompactLimitPreset)) {
                                wCustomAutoCompactTokens = autoCompactInput.getValue();
                            }
                            wCompactLimitPreset = value;
                            boolean custom = CUSTOM_COMPACT_LIMIT.equals(value);
                            autoCompactInput.active = custom;
                            if (custom) {
                                wAutoCompactTokens = wCustomAutoCompactTokens;
                                autoCompactInput.setValue(wAutoCompactTokens);
                            } else {
                                wAutoCompactTokens = value;
                                autoCompactInput.setValue(value);
                            }
                        });
        addRenderableWidget(compactLimitButton);
        autoCompactInput = vanillaField(settingsX + presetWidth + 4, compactY,
                settingsWidth - presetWidth - 4, 7, wAutoCompactTokens, "tokens");
        autoCompactInput.active = CUSTOM_COMPACT_LIMIT.equals(wCompactLimitPreset);
        autoCompactInput.setResponder(value -> {
            if (CUSTOM_COMPACT_LIMIT.equals(wCompactLimitPreset)) {
                wAutoCompactTokens = value;
                wCustomAutoCompactTokens = value;
            }
        });

        int footerY = settingsY + settingsGap * 11 + 4;
        int gap = 3;
        int quarter = (settingsWidth - gap * 3) / 4;
        int x = settingsX;
        addRenderableWidget(Button.builder(Component.literal("新增站点…"), b -> {
            preserveSettingsFields();
            addingSite = true;
            settingsScroll = 0;
            wSiteName = "";
            wBaseUrl = "";
            wFullUrl = false;
            wModel = "";
            diagnosticMessage = "";
            clearDetectedModels();
            discardSettingsWidgets();
            rebuild();
        }).bounds(x, footerY, quarter, 20).build());
        x += quarter + gap;
        connectionTestButton = addRenderableWidget(Button.builder(Component.literal("测试连接"), b -> startConnectionTest())
                .bounds(x, footerY, quarter, 20).build());
        x += quarter + gap;
        modelDetectionButton = addRenderableWidget(Button.builder(Component.literal("检测模型"), b -> startModelDetection())
                .bounds(x, footerY, quarter, 20).build());
        x += quarter + gap;
        updateDiagnosticButtons();
        addRenderableWidget(Button.builder(Component.literal("保存设置"), b -> saveSettings())
                .bounds(x, footerY, settingsX + settingsWidth - x, 20).build());
        capabilityDetectionButton = addRenderableWidget(Button.builder(
                        Component.literal("检测思考强度与联网能力"), b -> startCapabilityDetection())
                .bounds(settingsX, footerY + 25, settingsWidth, 20).build());
        settingsMaxScroll = Math.max(0, footerY + settingsScroll + 53 - (height - 32));
        refreshSettingsWidgetVisibility();
    }

    private void refreshSettingsWidgetVisibility() {
        int top = 0, bottom = height - 30;
        for (var child : children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget) {
                widget.visible = widget.getY() + widget.getHeight() >= top && widget.getY() <= bottom;
            }
        }
    }

    private static String reasoningLabel(String value) {
        return switch (value) {
            case "none" -> "关闭";
            case "minimal" -> "Minimal";
            case "low" -> "Low";
            case "medium" -> "Medium";
            case "high" -> "High";
            case "xhigh" -> "XHigh";
            default -> "自动";
        };
    }

    private void onProviderChanged(String value) {
        preserveSettingsFields();
        wProvider = value;
        wApiKey = "";
        wBaseUrl = providerDefaultBaseUrl(value);
        wFullUrl = false;
        clearDetectedModels();
        diagnosticMessage = "";
        ModelRegistry.Provider provider = ModelRegistry.provider(LlmProviders.normalize(value));
        customModel = provider != null && provider.custom();
        wModel = provider != null && !provider.models().isEmpty() ? provider.models().get(0).id() : "";
        discardSettingsWidgets();
        rebuild();
    }

    private void onModelChanged(String value) {
        preserveSettingsFields();
        if (CUSTOM_MODEL.equals(value)) {
            customModel = true;
            wModel = "";
            discardSettingsWidgets();
            rebuild();
        } else {
            wModel = value;
        }
    }

    private String providerDisplayName(String id) {
        ModelRegistry.Provider provider = ModelRegistry.provider(LlmProviders.normalize(id));
        return provider == null ? id : provider.name();
    }

    private static String providerDefaultBaseUrl(String id) {
        String normalized = LlmProviders.normalize(id);
        if ("deepseek".equals(normalized)) return "https://api.deepseek.com";
        return LlmProviders.byId(normalized).defaultBaseUrl();
    }

    private EditBox vanillaField(int x, int y, int w, int maxLength, String value, String hint) {
        EditBox field = new EditBox(font, x, y, Math.max(40, w), 20, Component.literal(hint));
        field.setMaxLength(maxLength);
        field.setValue(NumenScreenState.nullToEmpty(value));
        field.setHint(Component.literal(hint).withStyle(Style.EMPTY.withColor(FAINT)));
        addRenderableWidget(field);
        return field;
    }

    private void saveSettings() {
        preserveSettingsFields();
        Integer autoCompactTokens = parseAutoCompactTokens();
        if (autoCompactTokens == null) return;
        CompanionAiConfigStore.put(uuid, new CompanionAiConfigStore.Profile(wProvider, wApiKey,
                wModel.trim(), wBaseUrl.trim(), wFullUrl && !wBaseUrl.isBlank(), wProxy.trim(),
                wSystemPrompt, wReasoning, wWebSearch, wLowQuality, autoCompactTokens));
        NumenLlmClient.reset(uuid);
        savedFlashUntil = System.currentTimeMillis() + 1800;
    }

    private Integer parseAutoCompactTokens() {
        try {
            int value = Integer.parseInt(wAutoCompactTokens.trim());
            if (value < CompanionAiConfigStore.MIN_AUTO_COMPACT_TOKENS
                    || value > CompanionAiConfigStore.MAX_AUTO_COMPACT_TOKENS) {
                warningUntil = System.currentTimeMillis() + 4000;
                diagnosticMessage = "自动压缩阈值需在 "
                        + CompanionAiConfigStore.MIN_AUTO_COMPACT_TOKENS + "–"
                        + CompanionAiConfigStore.MAX_AUTO_COMPACT_TOKENS + " tokens 之间";
                diagnosticColor = FAILED;
                return null;
            }
            wAutoCompactTokens = Integer.toString(value);
            return value;
        } catch (NumberFormatException invalid) {
            warningUntil = System.currentTimeMillis() + 4000;
            diagnosticMessage = "自动压缩阈值必须是整数 tokens";
            diagnosticColor = FAILED;
            return null;
        }
    }

    private static String compactLimitPreset(int value) {
        String text = Integer.toString(value);
        return COMPACT_LIMIT_PRESETS.contains(text) ? text : CUSTOM_COMPACT_LIMIT;
    }

    private static Component compactLimitLabel(String value) {
        return Component.literal(CUSTOM_COMPACT_LIMIT.equals(value)
                ? "自定义" : NumenScreenState.formatTokens(Integer.parseInt(value)));
    }

    private int effectiveAutoCompactTokens() {
        int configured = CompanionAiConfigStore.normalizeAutoCompactTokens(parseIntOrDefault(
                wAutoCompactTokens, CompanionAiConfigStore.DEFAULT_AUTO_COMPACT_TOKENS));
        int window = ModelRegistry.contextWindow(LlmProviders.normalize(wProvider), wModel);
        return AgentContextPolicy.compactThreshold(window, configured);
    }

    private static int parseIntOrDefault(String value, int fallback) {
        try { return Integer.parseInt(value == null ? "" : value.trim()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private void startConnectionTest() {
        if (diagnosticRunning) return;
        preserveSettingsFields();
        LlmEndpointDiagnostics.Settings snapshot = diagnosticSettings();
        if (!validateDiagnosticSettings(snapshot, true)) {
            writeFailureDiagnostic("connection_test", snapshot,
                    new IllegalArgumentException(diagnosticMessage));
            return;
        }
        diagnosticRunning = true;
        diagnosticKind = DiagnosticKind.CONNECTION;
        diagnosticMessage = "正在请求当前模型…";
        diagnosticColor = RUNNING;
        updateDiagnosticButtons();
        LlmEndpointDiagnostics.testConnection(snapshot).whenComplete((result, error) -> runOnMinecraftThread(() -> {
            diagnosticRunning = false;
            diagnosticKind = null;
            if (error != null) {
                diagnosticMessage = diagnosticError(error);
                diagnosticColor = FAILED;
                writeFailureDiagnostic("connection_test", snapshot, error);
                if (!sameDiagnosticSettings(snapshot)) diagnosticMessage += " · 设置已改变";
                updateDiagnosticButtons();
                return;
            }
            if (!sameDiagnosticSettings(snapshot)) {
                diagnosticMessage = "设置已改变，请重新测试";
                diagnosticColor = FAILED;
                updateDiagnosticButtons();
                return;
            }
            String reply = result.reply().isBlank() ? "已返回有效响应" : "回复：" + NumenScreenState.oneLine(result.reply(), 36);
            diagnosticMessage = "连接成功 · " + result.elapsedMillis() + "ms · " + reply;
            diagnosticColor = OK;
            updateDiagnosticButtons();
        }));
    }

    private void startModelDetection() {
        if (diagnosticRunning) return;
        preserveSettingsFields();
        LlmEndpointDiagnostics.Settings snapshot = diagnosticSettings();
        if (!validateDiagnosticSettings(snapshot, false)) {
            writeFailureDiagnostic("model_detection", snapshot,
                    new IllegalArgumentException(diagnosticMessage));
            return;
        }
        diagnosticRunning = true;
        diagnosticKind = DiagnosticKind.MODELS;
        diagnosticMessage = "正在读取 /models…";
        diagnosticColor = RUNNING;
        clearDetectedModels();
        updateDiagnosticButtons();
        LlmEndpointDiagnostics.detectModels(snapshot).whenComplete((result, error) -> runOnMinecraftThread(() -> {
            diagnosticRunning = false;
            diagnosticKind = null;
            if (error != null) {
                diagnosticMessage = diagnosticError(error);
                diagnosticColor = FAILED;
                writeFailureDiagnostic("model_detection", snapshot, error);
                if (!sameDiagnosticSettings(snapshot)) diagnosticMessage += " · 设置已改变";
                updateDiagnosticButtons();
                return;
            }
            if (!sameDiagnosticSettings(snapshot)) {
                diagnosticMessage = "设置已改变，请重新检测";
                diagnosticColor = FAILED;
                updateDiagnosticButtons();
                return;
            }
            detectedModels = result.models();
            diagnosticMessage = "检测到 " + detectedModels.size() + " 个模型 · " + result.elapsedMillis()
                    + "ms · " + NumenScreenState.oneLine(String.join(", ", detectedModels), 60);
            diagnosticColor = OK;
            customModel = false;
            if (!detectedModels.contains(wModel)) wModel = detectedModels.get(0);
            discardSettingsWidgets();
            rebuild();
        }));
    }

    private LlmEndpointDiagnostics.Settings diagnosticSettings() {
        return new LlmEndpointDiagnostics.Settings(addingSite ? "openai" : wProvider,
                wApiKey.trim(), wModel.trim(),
                wBaseUrl.trim(), wProxy.trim(), wFullUrl, wReasoning);
    }

    private void startCapabilityDetection() {
        if (diagnosticRunning) return;
        preserveSettingsFields();
        LlmEndpointDiagnostics.Settings snapshot = diagnosticSettings();
        if (!validateDiagnosticSettings(snapshot, true)) return;
        diagnosticRunning = true;
        diagnosticKind = DiagnosticKind.CAPABILITIES;
        diagnosticMessage = "正在检测思考强度和全网搜索能力…";
        diagnosticColor = RUNNING;
        updateDiagnosticButtons();
        LlmEndpointDiagnostics.detectCapabilities(snapshot).whenComplete((result, error) -> runOnMinecraftThread(() -> {
            diagnosticRunning = false;
            diagnosticKind = null;
            if (error != null) {
                diagnosticMessage = diagnosticError(error);
                diagnosticColor = FAILED;
                writeFailureDiagnostic("capability_detection", snapshot, error);
            } else {
                detectedReasoning = result.reasoningEfforts();
                diagnosticMessage = "思考强度：" + (detectedReasoning.isEmpty() ? "未检测到" : String.join("/", detectedReasoning))
                        + "；全网搜索：" + (result.webReachable() ? "可用" : "不可用")
                        + "；" + result.elapsedMillis() + "ms";
                diagnosticColor = result.webReachable() ? OK : RUNNING;
                if (!detectedReasoning.isEmpty() && !detectedReasoning.contains(wReasoning)
                        && !"auto".equals(wReasoning) && !"none".equals(wReasoning)) wReasoning = "auto";
                discardSettingsWidgets();
                rebuild();
            }
            updateDiagnosticButtons();
        }));
    }

    private boolean validateDiagnosticSettings(LlmEndpointDiagnostics.Settings settings, boolean requireModel) {
        if (settings.apiKey().isBlank()) {
            diagnosticMessage = "API Key 为空";
            diagnosticColor = FAILED;
            return false;
        }
        if (addingSite && settings.baseUrl().isBlank()) {
            diagnosticMessage = "请先填写中转站 Base URL";
            diagnosticColor = FAILED;
            return false;
        }
        if (requireModel && settings.model().isBlank()) {
            diagnosticMessage = "请先填写或检测一个模型 ID";
            diagnosticColor = FAILED;
            return false;
        }
        return true;
    }

    private boolean sameDiagnosticSettings(LlmEndpointDiagnostics.Settings snapshot) {
        preserveSettingsFields();
        return snapshot.equals(diagnosticSettings());
    }

    private void updateDiagnosticButtons() {
        if (connectionTestButton != null) {
            connectionTestButton.active = !diagnosticRunning;
            connectionTestButton.setMessage(Component.literal(diagnosticRunning && diagnosticKind == DiagnosticKind.CONNECTION
                    ? "测试中 " + SPINNER[tickCounter % SPINNER.length] : "测试连接"));
        }
        if (modelDetectionButton != null) {
            modelDetectionButton.active = !diagnosticRunning;
            modelDetectionButton.setMessage(Component.literal(diagnosticRunning && diagnosticKind == DiagnosticKind.MODELS
                    ? "检测中 " + SPINNER[tickCounter % SPINNER.length] : "检测模型"));
        }
        if (capabilityDetectionButton != null) {
            capabilityDetectionButton.active = !diagnosticRunning;
            capabilityDetectionButton.setMessage(Component.literal(
                    diagnosticRunning && diagnosticKind == DiagnosticKind.CAPABILITIES
                            ? "检测能力中 " + SPINNER[tickCounter % SPINNER.length]
                            : "检测思考强度与联网能力"));
        }
    }

    private void clearDetectedModels() {
        detectedModels = List.of();
    }

    private void writeFailureDiagnostic(String operation, LlmEndpointDiagnostics.Settings settings,
                                        Throwable error) {
        if (AiFailureReporter.write(operation, settings, diagnosticMessage, error) != null) {
            diagnosticMessage += " · 诊断已写入 aifailure";
        } else {
            diagnosticMessage += " · 诊断文件写入失败";
        }
    }

    private void toggleFullUrl() {
        preserveSettingsFields();
        if (wBaseUrl.isBlank()) {
            wFullUrl = false;
            diagnosticMessage = "请先填写完整请求 URL";
            diagnosticColor = FAILED;
            updateFullUrlButton();
            return;
        }
        wFullUrl = !wFullUrl;
        diagnosticMessage = wFullUrl ? "完整 URL 已开启：不会自动拼接任何路径" : "完整 URL 已关闭：自动拼接接口路径";
        diagnosticColor = wFullUrl ? RUNNING : MUTED;
        clearDetectedModels();
        updateFullUrlButton();
    }

    private Component fullUrlLabel() {
        return Component.literal("完整 URL: " + (wFullUrl ? "开" : "关"));
    }

    private void updateFullUrlButton() {
        if (fullUrlButton == null) return;
        boolean hasUrl = baseUrlInput != null && !baseUrlInput.getValue().trim().isEmpty();
        if (!hasUrl) wFullUrl = false;
        fullUrlButton.active = hasUrl && !diagnosticRunning;
        fullUrlButton.setMessage(fullUrlLabel());
    }

    private void runOnMinecraftThread(Runnable action) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.screen == this) action.run();
        });
    }

    private static String diagnosticError(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof LlmHttpException http) {
            String detail = providerErrorMessage(http.responseBody());
            if (http.isUnauthorized()) return "鉴权失败（HTTP " + http.statusCode() + "）" + detail;
            if (http.isRateLimited()) return "请求过多/余额限制（HTTP 429）" + detail;
            if (http.statusCode() == 404) return "接口不存在（HTTP 404），检查 Base URL 是否包含 /v1" + detail;
            if (http.isServerError()) return "中转站服务异常（HTTP " + http.statusCode() + "）" + detail;
            return "请求失败（HTTP " + http.statusCode() + "）" + detail;
        }
        if (cause instanceof UnknownHostException) return "域名无法解析，请检查 Base URL 或网络";
        if (cause instanceof ConnectException) return "无法连接服务器，请检查地址、端口或代理";
        if (cause instanceof HttpTimeoutException || cause instanceof java.util.concurrent.TimeoutException) {
            return "连接超时，请检查中转站或代理";
        }
        if (cause instanceof IllegalArgumentException) return "设置/响应无效：" + NumenScreenState.oneLine(cause.getMessage(), 56);
        return cause.getClass().getSimpleName() + "：" + NumenScreenState.oneLine(cause.getMessage(), 56);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) cause = cause.getCause();
        return cause;
    }

    private static String providerErrorMessage(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String message = "";
            if (root.has("error") && root.get("error").isJsonObject()) {
                message = NumenScreenState.jsonString(root.getAsJsonObject("error"), "message");
            }
            if (message.isBlank()) message = NumenScreenState.jsonString(root, "message");
            return message.isBlank() ? "" : "：" + NumenScreenState.oneLine(message, 48);
        } catch (RuntimeException ignored) {
            return "：" + NumenScreenState.oneLine(body, 48);
        }
    }

    private void buildAddSiteControls() {
        settingsMaxScroll = 0;
        settingsY = 72;
        settingsGap = 27;
        siteNameInput = vanillaField(settingsX + settingsLabelWidth, settingsY,
                settingsWidth - settingsLabelWidth, 64, wSiteName, "例如：我的代理站点");
        apiKeyInput = vanillaField(settingsX + settingsLabelWidth, settingsY + settingsGap,
                settingsWidth - settingsLabelWidth, 512, wApiKey, "API Key");
        int modelY = settingsY + settingsGap * 2;
        if (!detectedModels.isEmpty() && !customModel) {
            List<String> choices = new ArrayList<>(detectedModels);
            choices.add(CUSTOM_MODEL);
            String initial = detectedModels.contains(wModel) ? wModel : detectedModels.get(0);
            wModel = initial;
            modelButton = CycleButton.<String>builder(id -> Component.literal(
                            CUSTOM_MODEL.equals(id) ? "自定义…" : id))
                    .withValues(choices)
                    .withInitialValue(initial)
                    .create(settingsX, modelY, settingsWidth, 20,
                            Component.literal("模型（检测 " + detectedModels.size() + "）"),
                            (button, value) -> onModelChanged(value));
            addRenderableWidget(modelButton);
        } else {
            int detectedW = detectedModels.isEmpty() ? 0 : 58;
            modelInput = vanillaField(settingsX + settingsLabelWidth, modelY,
                    settingsWidth - settingsLabelWidth - (detectedW == 0 ? 0 : detectedW + 4),
                    128, wModel, "模型 ID");
            if (detectedW > 0) {
                addRenderableWidget(Button.builder(Component.literal("检测结果"), b -> {
                    preserveSettingsFields();
                    customModel = false;
                    wModel = detectedModels.get(0);
                    discardSettingsWidgets();
                    rebuild();
                }).bounds(settingsX + settingsWidth - detectedW, modelY, detectedW, 20).build());
            }
        }
        int baseY = settingsY + settingsGap * 3;
        int fullUrlW = 82;
        baseUrlInput = vanillaField(settingsX + settingsLabelWidth, baseY,
                settingsWidth - settingsLabelWidth - fullUrlW - 4, 256, wBaseUrl, "https://…/v1");
        fullUrlButton = addRenderableWidget(Button.builder(fullUrlLabel(), b -> toggleFullUrl())
                .bounds(settingsX + settingsWidth - fullUrlW, baseY, fullUrlW, 20).build());
        updateFullUrlButton();
        int footerY = Math.min(height - 28, settingsY + settingsGap * 4 + 8);
        int gap = 3;
        int quarter = (settingsWidth - gap * 3) / 4;
        int x = settingsX;
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> {
            preserveSettingsFields();
            addingSite = false;
            diagnosticMessage = "";
            clearDetectedModels();
            loadSettingsState();
            discardSettingsWidgets();
            rebuild();
        }).bounds(x, footerY, quarter, 20).build());
        x += quarter + gap;
        connectionTestButton = addRenderableWidget(Button.builder(Component.literal("测试连接"), b -> startConnectionTest())
                .bounds(x, footerY, quarter, 20).build());
        x += quarter + gap;
        modelDetectionButton = addRenderableWidget(Button.builder(Component.literal("检测模型"), b -> startModelDetection())
                .bounds(x, footerY, quarter, 20).build());
        x += quarter + gap;
        updateDiagnosticButtons();
        addRenderableWidget(Button.builder(Component.literal("保存站点"), b -> saveCustomSite())
                .bounds(x, footerY, settingsX + settingsWidth - x, 20).build());
    }

    private void saveCustomSite() {
        preserveSettingsFields();
        String site = wSiteName.trim();
        String url = wBaseUrl.trim();
        if (site.isEmpty() || url.isEmpty()) {
            warningUntil = System.currentTimeMillis() + 4000;
            return;
        }
        String id = ModelRegistry.addCustomSite(site, url, wModel.trim());
        if (id == null) {
            warningUntil = System.currentTimeMillis() + 4000;
            return;
        }
        CompanionAiConfigStore.put(uuid, new CompanionAiConfigStore.Profile(id, wApiKey,
                wModel.trim(), url, wFullUrl, wProxy.trim(), wSystemPrompt,
                wReasoning, wWebSearch, wLowQuality,
                CompanionAiConfigStore.normalizeAutoCompactTokens(parseIntOrDefault(
                        wAutoCompactTokens, CompanionAiConfigStore.DEFAULT_AUTO_COMPACT_TOKENS))));
        NumenLlmClient.reset(uuid);
        wProvider = id;
        wBaseUrl = url;
        addingSite = false;
        customModel = true;
        savedFlashUntil = System.currentTimeMillis() + 1800;
        discardSettingsWidgets();
        rebuild();
    }

    /** Prevent init() from re-reading values from controls that belong to the page being replaced. */
    private void discardSettingsWidgets() {
        apiKeyInput = null;
        modelInput = null;
        baseUrlInput = null;
        proxyInput = null;
        systemPromptInput = null;
        autoCompactInput = null;
        siteNameInput = null;
        providerButton = null;
        modelButton = null;
        compactLimitButton = null;
        connectionTestButton = null;
        modelDetectionButton = null;
        capabilityDetectionButton = null;
        fullUrlButton = null;
        reasoningButton = null;
        webSearchButton = null;
        lowQualityButton = null;
    }

    @Override
    public void tick() {
        if (chatInput != null) chatInput.tick();
        if (summonInput != null) summonInput.tick();
        if (apiKeyInput != null) apiKeyInput.tick();
        if (modelInput != null) modelInput.tick();
        if (baseUrlInput != null) baseUrlInput.tick();
        if (proxyInput != null) proxyInput.tick();
        if (systemPromptInput != null) systemPromptInput.tick();
        if (autoCompactInput != null) autoCompactInput.tick();
        if (siteNameInput != null) siteNameInput.tick();
        if (healthInput != null) healthInput.tick();
        if (damageInput != null) damageInput.tick();
        if (attackSpeedInput != null) attackSpeedInput.tick();
        if (speedInput != null) speedInput.tick();
        if (armorInput != null) armorInput.tick();
        if (toughnessInput != null) toughnessInput.tick();
        if (knockbackInput != null) knockbackInput.tick();
        if (luckInput != null) luckInput.tick();
        if (respawnInput != null) respawnInput.tick();
        tickCounter++;
        if (page == Page.SETTINGS) {
            if (diagnosticRunning) updateDiagnosticButtons();
            updateFullUrlButton();
        }

        if (!summoning && dismissPending == null && rosterSignature != rosterSignature()) {
            List<NumenRoster.Entry> entries = NumenRoster.instance().entries();
            if (uuid == null && !entries.isEmpty()) {
                uuid = entries.get(0).uuid();
                name = entries.get(0).name();
            } else if (uuid != null && entries.stream().noneMatch(e -> e.uuid().equals(uuid))) {
                NumenRoster.Entry first = entries.isEmpty() ? null : entries.get(0);
                uuid = first == null ? null : first.uuid();
                name = first == null ? null : first.name();
            }
            rebuild();
            return;
        }

        if (page == Page.TASKS && taskUiSignature != taskUiSignature()) { rebuild(); return; }
        if (page == Page.DATA && uuid != null) { ClientCompanionSettings.Snapshot s=ClientCompanionSettings.get(uuid); long received=s==null?0:s.receivedAt(); if(received!=dataReceivedAt){rebuild();return;} }
        if (uuid != null && page == Page.CHAT) {
            if (stopButton != null) stopButton.active = loop().canInterrupt();
            if (compactButton != null) compactButton.active = loop().canCompact();
        }
    }

    private int rosterSignature() {
        int result = 1;
        for (NumenRoster.Entry entry : NumenRoster.instance().entries()) {
            result = 31 * result + entry.uuid().hashCode();
            result = 31 * result + entry.name().hashCode();
        }
        return result;
    }

    private int taskUiSignature(){if(uuid==null)return 0;ClientTaskList.Snapshot s=ClientTaskList.get(uuid);if(s==null)return 0;int h=Boolean.hashCode(s.queuePaused());for(var e:s.tasks()){h=31*h+e.toolCallId().hashCode();h=31*h+e.state().hashCode();h=31*h+Boolean.hashCode(e.paused());}return h;}

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, Component.literal("Numen AI 同伴"), width / 2, 9, WHITE);

        if (summoning) {
            renderSummonDialog(graphics);
        } else if (dismissPending != null) {
            renderDismissDialog(graphics);
        } else {
            switch (page) {
                case CHAT -> renderChat(graphics, mouseX, mouseY);
                case TASKS -> renderTasks(graphics);
                case SETTINGS -> renderSettings(graphics);
                case DATA -> renderData(graphics);
                case INVENTORY -> { }
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderSummonDialog(GuiGraphics graphics) {
        graphics.drawCenteredString(font, Component.literal("召唤新的 AI 同伴"), width / 2,
                Math.max(62, height / 2 - 38), WHITE);
        graphics.drawCenteredString(font, Component.literal("输入名称后按 Enter 或点击召唤"), width / 2,
                Math.max(74, height / 2 - 26), MUTED);
    }

    private void renderDismissDialog(GuiGraphics graphics) {
        String targetName = providerNameForCompanion(dismissPending);
        graphics.drawCenteredString(font, Component.literal("永久删除 “" + targetName + "”？"),
                width / 2, Math.max(62, height / 2 - 38), FAILED);
        graphics.drawCenteredString(font, Component.literal("背包会掉落在原地，此操作无法撤销"),
                width / 2, Math.max(74, height / 2 - 26), MUTED);
    }

    private void renderChat(GuiGraphics graphics, int mouseX, int mouseY) {
        if (uuid == null) {
            graphics.drawCenteredString(font, Component.literal("还没有同伴，点击“召唤”创建一个。"),
                    width / 2, height / 2, MUTED);
            return;
        }

        int top = 76;
        int bottom = height - 34;
        int left = 10;
        int totalW = width - 20;
        int statusW = totalW >= 430 ? 145 : totalW >= 340 ? 118 : 0;
        int transcriptW = totalW - (statusW == 0 ? 0 : statusW + 6);
        int statusX = left + transcriptW + 6;

        drawVanillaPanel(graphics, left, top, transcriptW, bottom - top);
        if (statusW > 0) drawVanillaPanel(graphics, statusX, top, statusW, bottom - top);

        List<Row> rows = buildTranscriptRows(transcriptW - 12);
        int viewTop = top + 5;
        int viewBottom = bottom - 5;
        int viewH = viewBottom - viewTop;
        lastMaxScroll = Math.max(0, rows.size() * LINE_H - viewH);
        if (pinChatBottom) chatScroll = lastMaxScroll;
        chatScroll = Math.max(0, Math.min(chatScroll, lastMaxScroll));

        graphics.enableScissor(left + 4, viewTop, left + transcriptW - 5, viewBottom);
        int y = viewTop - chatScroll;
        long now = System.currentTimeMillis();
        List<ConvoState.Msg> messages = loop().display();
        Set<String> done = NumenScreenState.completedToolIds(messages);
        Set<String> failed = NumenScreenState.failedToolIds(messages);
        for (Row row : rows) {
            if (y + LINE_H > viewTop && y < viewBottom) {
                if (row.toolIds() != null) {
                    boolean running = row.toolIds().stream().anyMatch(id -> !done.contains(id));
                    boolean anyFailed = row.toolIds().stream().anyMatch(failed::contains);
                    String glyph = running ? SPINNER[(int) ((now / 120) % SPINNER.length)]
                            : anyFailed ? "!" : "✓";
                    int color = running ? RUNNING : anyFailed ? FAILED : OK;
                    graphics.drawString(font, Component.literal(glyph), left + 6, y, color, true);
                    graphics.drawString(font, row.text(), left + 17, y, row.color(), true);
                } else {
                    graphics.drawString(font, row.text(), left + 6, y, row.color(), true);
                }
            }
            y += LINE_H;
        }
        graphics.disableScissor();

        if (lastMaxScroll > 0) {
            int trackH = viewH;
            int thumbH = Math.max(12, trackH * viewH / (viewH + lastMaxScroll));
            int thumbY = viewTop + (trackH - thumbH) * chatScroll / lastMaxScroll;
            graphics.fill(left + transcriptW - 4, viewTop, left + transcriptW - 2, viewBottom, 0xFF303030);
            graphics.fill(left + transcriptW - 4, thumbY, left + transcriptW - 2, thumbY + thumbH, 0xFFA0A0A0);
        }

        if (statusW > 0) renderTaskStatus(graphics, statusX, top, statusW, bottom - top);
        if (warningUntil > System.currentTimeMillis()) {
            graphics.drawString(font, Component.literal("未配置 API Key，请先打开 AI 设置。"),
                    12, height - 40, FAILED, true);
        }
        if (ClientDeaths.isDead(uuid)) {
            int seconds = (int) Math.ceil(ClientDeaths.remainingMs(uuid) / 1000.0);
            graphics.drawString(font, Component.literal(name + " 将在 " + seconds + " 秒后复活"),
                    12, 67, FAILED, true);
        }
    }

    private void drawVanillaPanel(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0xA0000000);
        graphics.renderOutline(x, y, w, h, 0xFF555555);
    }

    private List<Row> buildTranscriptRows(int width) {
        List<Row> rows = new ArrayList<>();
        List<ConvoState.Msg> messages = loop().display();
        Set<String> done = NumenScreenState.completedToolIds(messages);
        Set<String> failed = NumenScreenState.failedToolIds(messages);
        List<LlmToolCall> toolGroup = new ArrayList<>();
        for (ConvoState.Msg message : messages) {
            if (message instanceof ConvoState.Msg.User user) {
                flushToolGroup(rows, toolGroup, done, failed, width);
                if (ConvoLog.COMPACT_DIVIDER.equals(user.content())) {
                    wrap(rows, "—— 更早的对话已压缩为摘要（原文仍保存在磁盘）——", FAINT, width);
                } else {
                    addHeader(rows, "你", USER, width);
                    wrap(rows, user.content(), WHITE, width);
                }
            } else if (message instanceof ConvoState.Msg.Assistant assistantMessage) {
                AssistantTurn turn = assistantMessage.turn();
                if (turn.content() != null && !turn.content().isBlank()) {
                    flushToolGroup(rows, toolGroup, done, failed, width);
                    addHeader(rows, name == null ? "Numen" : name, ASSISTANT, width);
                    wrap(rows, turn.content(), WHITE, width);
                }
                toolGroup.addAll(turn.toolCalls());
            }
        }
        flushToolGroup(rows, toolGroup, done, failed, width);
        for (String queued : loop().queuedPrompts()) {
            wrap(rows, "[排队] " + queued, RUNNING, width);
        }
        if (loop().isCompacting()) wrap(rows, "正在压缩对话历史…", RUNNING, width);
        if (rows.isEmpty()) wrap(rows, "开始和 " + name + " 对话。", MUTED, width);
        return rows;
    }

    private void flushToolGroup(List<Row> rows, List<LlmToolCall> group,
                                Set<String> done, Set<String> failed, int width) {
        if (group.isEmpty()) return;
        if (group.size() == 1) {
            addToolRow(rows, group.get(0), width);
            group.clear();
            return;
        }
        String key = group.get(0).id();
        boolean running = group.stream().anyMatch(call -> !done.contains(call.id()));
        boolean expanded = running || expandedToolGroups.contains(key);
        if (!expanded) {
            List<String> names = new ArrayList<>();
            for (LlmToolCall call : group) if (!names.contains(call.name())) names.add(call.name());
            boolean anyFailed = group.stream().anyMatch(call -> failed.contains(call.id()));
            String summary = "▶ " + group.size() + " 个步骤 · " + String.join(" · ", names);
            rows.add(new Row(colored(fitOneLine(summary, width), anyFailed ? FAILED : TOOL)
                    .getVisualOrderText(), anyFailed ? FAILED : TOOL, null, key));
        } else {
            if (!running) {
                rows.add(new Row(colored("▼ " + group.size() + " 个步骤", MUTED).getVisualOrderText(),
                        MUTED, null, key));
            }
            for (LlmToolCall call : group) addToolRow(rows, call, width);
        }
        group.clear();
    }

    private void addToolRow(List<Row> rows, LlmToolCall call, int width) {
        rows.add(new Row(colored(fitOneLine(toolLine(call), width - 12), TOOL).getVisualOrderText(),
                TOOL, List.of(call.id()), null));
    }

    private String toolLine(LlmToolCall call) {
        String args = call.arguments() == null ? "" : call.arguments().replaceAll("\\s+", " ").trim();
        if (args.length() > TOOL_ARG_CHARS) args = args.substring(0, TOOL_ARG_CHARS) + "…";
        return call.name() + (args.isEmpty() ? "" : "  " + args);
    }

    private String fitOneLine(String text, int pixelWidth) {
        if (font.width(text) <= pixelWidth) return text;
        String result = text;
        while (result.length() > 1 && font.width(result + "…") > pixelWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + "…";
    }

    private void addHeader(List<Row> rows, String label, int color, int width) {
        Component component = colored(label, color).copy().withStyle(style -> style.withBold(true));
        for (FormattedCharSequence line : font.split(component, width)) {
            rows.add(new Row(line, color, null, null));
        }
    }

    private void wrap(List<Row> rows, String text, int color, int width) {
        for (FormattedCharSequence line : font.split(colored(text, color), width)) {
            rows.add(new Row(line, color, null, null));
        }
    }

    private static Component colored(String text, int color) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)));
    }

    private void renderTaskStatus(GuiGraphics graphics, int x, int y, int w, int h) {
        List<StatusLine> lines = buildStatusLines(w - 10);
        int top = y + 5;
        int bottom = y + h - 5;
        graphics.enableScissor(x + 3, top, x + w - 3, bottom);
        int lineY = top;
        for (StatusLine line : lines) {
            if (lineY + LINE_H > bottom) break;
            graphics.drawString(font, line.text(), x + 5, lineY, line.color(), true);
            lineY += LINE_H;
        }
        graphics.disableScissor();
    }

    private List<StatusLine> buildStatusLines(int width) {
        List<StatusLine> lines = new ArrayList<>();
        EntityAgentLoop agent = loop();
        String state = agent.isCompacting() ? "压缩历史"
                : agent.isBusy() ? "执行中"
                : agent.hasQueuedPrompts() ? "消息排队"
                : "空闲";
        int stateColor = agent.isBusy() ? RUNNING : agent.hasQueuedPrompts() ? RUNNING : OK;
        statusWrap(lines, "状态：" + state, stateColor, width);

        List<LlmToolCall> active = activeToolCalls();
        if (!active.isEmpty()) {
            statusSection(lines, "执行工具", width);
            for (LlmToolCall call : active) statusWrap(lines, "• " + toolLine(call), RUNNING, width);
        }

        JsonArray plan = latestPlan();
        statusSection(lines, "计划", width);
        if (plan == null || plan.isEmpty()) {
            statusWrap(lines, "暂无计划", FAINT, width);
        } else {
            for (int i = 0; i < plan.size(); i++) {
                if (!plan.get(i).isJsonObject()) continue;
                JsonObject item = plan.get(i).getAsJsonObject();
                String status = NumenScreenState.jsonString(item, "status");
                String glyph = switch (status) {
                    case "completed" -> "✓";
                    case "in_progress" -> "▶";
                    case "cancelled" -> "×";
                    default -> "•";
                };
                int color = switch (status) {
                    case "completed" -> OK;
                    case "in_progress" -> RUNNING;
                    case "cancelled" -> FAILED;
                    default -> MUTED;
                };
                statusWrap(lines, glyph + " " + NumenScreenState.jsonString(item, "content"), color, width);
            }
        }

        List<LongTermMemory.Entry> memories = loop().longTermMemory().search("", "", 3);
        if (!memories.isEmpty()) {
            statusSection(lines, "长期记忆", width);
            for (LongTermMemory.Entry memory : memories) {
                String line = memory.label() + "：" + memory.content();
                if (memory.x() != null && memory.y() != null && memory.z() != null) {
                    line += " @ " + memory.x() + "," + memory.y() + "," + memory.z();
                }
                statusWrap(lines, line, MUTED, width);
            }
        }

        List<String> failures = recentFailures(2);
        if (!failures.isEmpty()) {
            statusSection(lines, "最近失败", width);
            for (String failure : failures) statusWrap(lines, "! " + failure, FAILED, width);
        }
        return lines;
    }

    private void statusSection(List<StatusLine> lines, String title, int width) {
        if (!lines.isEmpty()) lines.add(new StatusLine(FormattedCharSequence.forward(" ", Style.EMPTY), MUTED));
        statusWrap(lines, title, WHITE, width);
    }

    private void statusWrap(List<StatusLine> lines, String text, int color, int width) {
        for (FormattedCharSequence line : font.split(colored(text, color), width)) {
            lines.add(new StatusLine(line, color));
        }
    }

    private List<LlmToolCall> activeToolCalls() {
        return NumenScreenState.activeToolCalls(loop().display());
    }

    private JsonArray latestPlan() {
        return NumenScreenState.latestPlan(loop().display());
    }

    private List<String> recentFailures(int max) {
        return NumenScreenState.recentFailures(loop().display(), max);
    }

    private void renderTasks(GuiGraphics graphics) {
        if (uuid == null) { graphics.drawCenteredString(font, Component.literal("还没有伙伴"), width/2, height/2, MUTED); return; }
        int x=12,y=78; graphics.drawString(font,Component.literal("长期计划（AI）"),x,y,WHITE,true); y+=13;
        var goals=loop().autonomyMemory().goals();
        int visibleGoals = visibleTaskGoalRows();
        if(goals.isEmpty()){
            graphics.drawString(font,Component.literal("暂无计划；长任务会在这里显示阶段。"),x,y,MUTED,false);
        } else {
            for(int i=0;i<visibleGoals;i++){
                var goal=goals.get(i);
                graphics.drawString(font,Component.literal("["+goal.status()+"] "+fitOneLine(goal.content(),width-30)),x,y,goal.status().equals("completed")?OK:RUNNING,false);
                y+=11;
            }
            if(goals.size()>visibleGoals) graphics.drawString(font,Component.literal("… 还有 "+(goals.size()-visibleGoals)+" 项长期计划"),x,y,MUTED,false);
        }
        y=taskQueueHeaderY();graphics.drawString(font,Component.literal("服务器执行队列"),x,y,WHITE,true);y+=14;
        ClientTaskList.Snapshot snapshot=ClientTaskList.get(uuid);
        if(snapshot==null){graphics.drawString(font,Component.literal("正在读取任务…"),x,y,MUTED,false);return;}
        if(snapshot.tasks().isEmpty()){graphics.drawString(font,Component.literal("当前没有准备或正在执行的任务"),x,y,MUTED,false);return;}
        for(ClientTaskList.Entry task:snapshot.tasks()){
            String marker=task.active()?"正在做":"准备做";if(task.paused())marker="已暂停";
            graphics.drawString(font,Component.literal(marker+" · "+fitOneLine(task.description(),Math.max(80,width-145))),x,y,task.paused()?MUTED:RUNNING,false);
            StringBuilder detail=new StringBuilder(task.phase());
            if(task.progressTotal()>0)detail.append(" · ").append(task.progressCurrent()).append('/').append(task.progressTotal());
            else if(task.progressCurrent()>0)detail.append(" · 已完成 ").append(task.progressCurrent());
            if(task.etaSeconds()>=0)detail.append(" · 预计 ").append(NumenScreenState.formatEta(task.etaSeconds()));
            if(!task.blocker().isBlank())detail.append(" · ").append(task.blocker());
            graphics.drawString(font,Component.literal(fitOneLine(detail.toString(),Math.max(80,width-145))),x,y+10,task.blocker().isBlank()?FAINT:FAILED,false);
            y+=24;if(y>height-20)break;
        }
    }

    private int visibleTaskGoalRows() {
        if (uuid == null) return 1;
        int maxRows = Math.max(1, Math.min(8, (height - 172) / 11));
        return Math.min(loop().autonomyMemory().goals().size(), maxRows);
    }

    private int taskQueueHeaderY() {
        int goalCount = loop().autonomyMemory().goals().size();
        int visible = visibleTaskGoalRows();
        int rows = goalCount == 0 ? 1 : visible + (goalCount > visible ? 1 : 0);
        return 78 + 13 + rows * 11 + 9;
    }

    private int taskQueueContentY() { return taskQueueHeaderY() + 14; }

    private void renderData(GuiGraphics graphics) {
        if(uuid==null){graphics.drawCenteredString(font,Component.literal("先召唤或选择一个伙伴"),width/2,height/2,MUTED);return;}
        graphics.drawCenteredString(font,Component.literal("这些数据只作用于当前伙伴，并保存在世界中"),width/2,65,MUTED);
        int w=Math.min(430,width-24),x=(width-w)/2,y=79,gap=24,fieldW=(w-4)/2;
        drawDataLabel(graphics,"最大生命",x,y+gap,fieldW);
        drawDataLabel(graphics,"攻击伤害",x+(w+4)/2,y+gap,fieldW);
        drawDataLabel(graphics,"攻击速度",x,y+gap*2,fieldW);
        drawDataLabel(graphics,"移动速度",x+(w+4)/2,y+gap*2,fieldW);
        drawDataLabel(graphics,"护甲",x,y+gap*3,fieldW);
        drawDataLabel(graphics,"护甲韧性",x+(w+4)/2,y+gap*3,fieldW);
        drawDataLabel(graphics,"击退抗性",x,y+gap*4,fieldW);
        drawDataLabel(graphics,"幸运",x+(w+4)/2,y+gap*4,fieldW);
        drawDataLabel(graphics,"复活秒数",x,y+gap*5,fieldW);
        if(System.currentTimeMillis()<warningUntil)graphics.drawCenteredString(font,Component.literal("请输入有效数字"),width/2,height-28,FAILED);
    }

    private void drawDataLabel(GuiGraphics graphics,String label,int x,int y,int groupW){graphics.drawString(font,Component.literal(label),x+3,y+6,MUTED,true);}

    private void renderSettings(GuiGraphics graphics) {
        if (uuid == null) {
            graphics.drawCenteredString(font, Component.literal("请先召唤或选择一个伙伴，再为它配置独立 AI。"),
                    width / 2, height / 2, MUTED);
            return;
        }
        graphics.drawCenteredString(font, Component.literal(addingSite ? "新增 OpenAI 兼容站点" : "AI 模型设置"),
                width / 2, 73 - settingsScroll, WHITE);
        if (addingSite) {
            drawSettingLabel(graphics, "站点名称", settingsY);
            drawSettingLabel(graphics, "API Key", settingsY + settingsGap);
            drawSettingLabel(graphics, "模型 ID", settingsY + settingsGap * 2);
            drawSettingLabel(graphics, "Base URL", settingsY + settingsGap * 3);
        } else {
            int modelY = settingsY + settingsGap;
            if (modelInput != null) drawSettingLabel(graphics, "模型", modelY);
            drawSettingLabel(graphics, "API Key", settingsY + settingsGap * 2);
            drawSettingLabel(graphics, "Base URL", settingsY + settingsGap * 3);
            drawSettingLabel(graphics, "代理", settingsY + settingsGap * 4);
            drawSettingLabel(graphics, "系统提示", settingsY + settingsGap * 5);
        }
        if (!addingSite) {
            drawSettingLabel(graphics, "思考强度", settingsY + settingsGap * 6);
            drawSettingLabel(graphics, "联网搜索", settingsY + settingsGap * 7);
        }
        if (!addingSite) drawSettingLabel(graphics, "Skill 模式", settingsY + settingsGap * 8);
        if (!addingSite) {
            int compactInfoY = settingsY + settingsGap * 10 + 6;
            int configured = CompanionAiConfigStore.normalizeAutoCompactTokens(parseIntOrDefault(
                    wAutoCompactTokens, CompanionAiConfigStore.DEFAULT_AUTO_COMPACT_TOKENS));
            int effective = effectiveAutoCompactTokens();
            String info = "配置 " + NumenScreenState.formatTokens(configured)
                    + " · 实际 " + NumenScreenState.formatTokens(effective);
            EntityAgentLoop currentLoop = AgentLoopRegistry.get(uuid).orElse(null);
            if (currentLoop == null) info += " · 尚未压缩";
            else {
                long cooldown = currentLoop.compactCooldownRemainingMs();
                if (currentLoop.isCompacting()) info += " · " + currentLoop.lastCompactionStatus();
                else if (cooldown > 0) info += " · " + currentLoop.lastCompactionStatus()
                        + "（剩余 " + Math.max(1L, (cooldown + 59_999L) / 60_000L) + " 分钟）";
                else if (currentLoop.lastCompactionDurationMs() > 0) {
                    info += " · " + currentLoop.lastCompactionStatus()
                            + " " + currentLoop.lastCompactionDurationMs() + "ms";
                } else info += " · " + currentLoop.lastCompactionStatus();
            }
            graphics.drawString(font, Component.literal(NumenScreenState.oneLine(info,
                    Math.max(28, settingsWidth / 5))), settingsX + 3, compactInfoY, MUTED, false);
        }
        if (settingsMaxScroll > 0) {
            int top = 78, bottom = height - 34;
            int thumb = Math.max(18, (bottom - top) * (bottom - top) / (bottom - top + settingsMaxScroll));
            int track = Math.max(1, bottom - top - thumb);
            int y = top + (int) ((long) settingsScroll * track / settingsMaxScroll);
            graphics.fill(settingsX + settingsWidth + 6, top, settingsX + settingsWidth + 8, bottom, 0x60404040);
            graphics.fill(settingsX + settingsWidth + 5, y, settingsX + settingsWidth + 9, y + thumb, 0xFFA0A0A0);
        }
        if (savedFlashUntil > System.currentTimeMillis()) {
            graphics.drawCenteredString(font, Component.literal("设置已保存"), width / 2, height - 39, OK);
        } else if (warningUntil > System.currentTimeMillis()) {
            String warning = addingSite ? "站点名称和 Base URL 不能为空"
                    : diagnosticMessage.isBlank() ? "请检查设置"
                    : NumenScreenState.oneLine(diagnosticMessage, Math.max(24, settingsWidth / 5));
            graphics.drawCenteredString(font, Component.literal(warning), width / 2, height - 39, FAILED);
        } else if (!diagnosticMessage.isBlank()) {
            graphics.drawCenteredString(font, Component.literal(NumenScreenState.oneLine(diagnosticMessage,
                    Math.max(24, settingsWidth / 5))), width / 2, height - 39, diagnosticColor);
        }
    }

    private void drawSettingLabel(GuiGraphics graphics, String label, int y) {
        if (y + 10 < 0 || y > height - 30) return;
        graphics.drawString(font, Component.literal(label), settingsX + 3, y + 6, MUTED, true);
    }

    /* Removed snapshot-based inventory implementation; native CompanionInventoryMenu owns this UI.
    private void renderInventory(GuiGraphics graphics, int mouseX, int mouseY) {
        if (uuid == null) {
            graphics.drawCenteredString(font, Component.literal("还没有同伴，点击“召唤”创建一个。"),
                    width / 2, height / 2, MUTED);
            return;
        }

        int[] layout = inventoryLayout();
        int x = layout[0];
        int y = layout[1];
        int ownerX = layout[2];
        graphics.blit(INVENTORY_TEXTURE, x, y, 0, 0, 176, 166);
        graphics.blit(INVENTORY_TEXTURE, ownerX, y, 0, 76, 176, 90);
        graphics.drawString(font, Component.literal("合成"), x + 97, y + 6, 0x404040, false);
        graphics.drawCenteredString(font, Component.literal("伙伴背包（含 2×2 合成）"), x + 88, y - 11, MUTED);
        graphics.drawCenteredString(font, Component.literal("你的背包"), ownerX + 88, y - 11, MUTED);
        graphics.drawCenteredString(font, Component.literal("先点物品，再点目标槽：可跨背包移动、合并或交换"),
                width / 2, y - 22, MUTED);

        ClientNumenInventory.Snapshot snapshot = ClientNumenInventory.get(uuid).orElse(null);
        AbstractClientPlayer entity = ClientNumenLookup.resolve(uuid);
        if (entity != null) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, x + 51, y + 75, 30,
                    (float) (x + 51) - mouseX, (float) (y + 25) - mouseY, entity);
        }

        if (snapshot == null) {
            graphics.drawCenteredString(font, Component.literal("正在读取伙伴背包…"), x + 88, y + 78, MUTED);
            return;
        }
        if (!snapshot.loaded()) {
            graphics.fill(x + 7, y + 82, x + 169, y + 160, 0xB0000000);
            graphics.drawCenteredString(font, Component.literal("同伴当前未加载，无法读取背包"),
                    x + 88, y + 112, FAILED);
            return;
        }

        List<ItemStack> craft = snapshot.craft();
        renderInventorySlot(graphics, stackAt(craft, 0), x + 98, y + 18, mouseX, mouseY, null);
        renderInventorySlot(graphics, stackAt(craft, 1), x + 116, y + 18, mouseX, mouseY, null);
        renderInventorySlot(graphics, stackAt(craft, 2), x + 98, y + 36, mouseX, mouseY, null);
        renderInventorySlot(graphics, stackAt(craft, 3), x + 116, y + 36, mouseX, mouseY, null);
        renderInventorySlot(graphics, stackAt(craft, 4), x + 154, y + 28, mouseX, mouseY, null);

        List<ItemStack> equipment = snapshot.equipment();
        for (int i = 0; i < 4; i++) {
            renderInventorySlot(graphics, stackAt(equipment, i), x + 8, y + 8 + i * 18,
                    mouseX, mouseY, ARMOR_EMPTY_ICONS[i]);
        }
        renderInventorySlot(graphics, stackAt(equipment, 4), x + 77, y + 62,
                mouseX, mouseY, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD);

        List<ItemStack> items = snapshot.items();
        for (int slot = 9; slot < 36; slot++) {
            int col = (slot - 9) % 9;
            int row = (slot - 9) / 9;
            renderInventorySlot(graphics, stackAt(items, slot), x + 8 + col * 18, y + 84 + row * 18,
                    mouseX, mouseY, null);
        }
        for (int slot = 0; slot < 9; slot++) {
            renderInventorySlot(graphics, stackAt(items, slot), x + 8 + slot * 18, y + 142,
                    mouseX, mouseY, null);
        }
        if (minecraft.player != null) {
            List<ItemStack> ownerItems = minecraft.player.getInventory().items;
            for (int slot = 9; slot < 36; slot++) {
                int n = slot - 9;
                renderInventorySlot(graphics, stackAt(ownerItems, slot), ownerX + 8 + (n % 9) * 18,
                        y + 8 + (n / 9) * 18, mouseX, mouseY, null);
            }
            for (int slot = 0; slot < 9; slot++) {
                renderInventorySlot(graphics, stackAt(ownerItems, slot), ownerX + 8 + slot * 18,
                        y + 66, mouseX, mouseY, null);
            }
        }

        if (selectedInventorySlot >= 0) {
            int[] pos = inventorySlotPosition(selectedInventorySlot, x, y, ownerX);
            if (pos != null) graphics.fill(pos[0], pos[1], pos[0] + 16, pos[1] + 16, 0x80FFFF00);
        }
    }

    private void renderInventorySlot(GuiGraphics graphics, ItemStack stack, int x, int y,
                                     int mouseX, int mouseY, ResourceLocation emptyIcon) {
        if (stack.isEmpty()) {
            if (emptyIcon != null) {
                TextureAtlasSprite sprite = minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(emptyIcon);
                graphics.blit(x, y, 0, 16, 16, sprite);
            }
        } else {
            graphics.renderItem(stack, x, y);
            graphics.renderItemDecorations(font, stack, x, y);
        }
        if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
            AbstractContainerScreen.renderSlotHighlight(graphics, x, y, 0);
            if (!stack.isEmpty()) hoveredInventoryStack = stack;
        }
    }

    private static ItemStack stackAt(List<ItemStack> stacks, int index) {
        return stacks != null && index >= 0 && index < stacks.size() && stacks.get(index) != null
                ? stacks.get(index) : ItemStack.EMPTY;
    }

    private int[] inventoryLayout() {
        int total = 176 * 2 + 12;
        int companionX = (width - total) / 2;
        return new int[]{companionX, Math.max(68, Math.min(74, height - 170)), companionX + 188};
    }

    private int inventorySlotAt(int mouseX, int mouseY) {
        int[] layout = inventoryLayout();
        int x = layout[0], y = layout[1], ownerX = layout[2];
        for (int i = 0; i <= 40; i++) {
            int[] p = inventorySlotPosition(i, x, y, ownerX);
            if (insideSlot(mouseX, mouseY, p)) return i;
        }
        for (int i = MoveCompanionInventoryPayload.CRAFT_INPUT_FIRST;
             i <= MoveCompanionInventoryPayload.CRAFT_RESULT; i++) {
            int[] p = inventorySlotPosition(i, x, y, ownerX);
            if (insideSlot(mouseX, mouseY, p)) return i;
        }
        for (int i = MoveCompanionInventoryPayload.OWNER_OFFSET;
             i <= MoveCompanionInventoryPayload.OWNER_LAST; i++) {
            int[] p = inventorySlotPosition(i, x, y, ownerX);
            if (insideSlot(mouseX, mouseY, p)) return i;
        }
        return -1;
    }

    private static boolean insideSlot(int mouseX, int mouseY, int[] position) {
        return position != null && mouseX >= position[0] && mouseX < position[0] + 16
                && mouseY >= position[1] && mouseY < position[1] + 16;
    }

    private static int[] inventorySlotPosition(int slot, int x, int y, int ownerX) {
        if(slot>=0&&slot<9)return new int[]{x+8+slot*18,y+142};
        if(slot>=9&&slot<36){int n=slot-9;return new int[]{x+8+(n%9)*18,y+84+(n/9)*18};}
        if(slot>=36&&slot<=39)return new int[]{x+8,y+8+(39-slot)*18};
        if(slot==40)return new int[]{x+77,y+62};
        if(slot>=100&&slot<=103){int n=slot-100;return new int[]{x+98+(n%2)*18,y+18+(n/2)*18};}
        if(slot==MoveCompanionInventoryPayload.CRAFT_RESULT)return new int[]{x+154,y+28};
        if(slot>=MoveCompanionInventoryPayload.OWNER_OFFSET&&slot<=MoveCompanionInventoryPayload.OWNER_LAST){
            int inventorySlot=slot-MoveCompanionInventoryPayload.OWNER_OFFSET;
            if(inventorySlot<9)return new int[]{ownerX+8+inventorySlot*18,y+66};
            int n=inventorySlot-9;return new int[]{ownerX+8+(n%9)*18,y+8+(n/9)*18};
        }
        return null;
    }

    private ItemStack inventoryStack(int slot) {
        ClientNumenInventory.Snapshot snapshot = ClientNumenInventory.get(uuid).orElse(null);
        if (snapshot == null || !snapshot.loaded()) return ItemStack.EMPTY;
        if (slot >= 0 && slot < 36) return stackAt(snapshot.items(), slot);
        if (slot >= 36 && slot <= 39) return stackAt(snapshot.equipment(), 39 - slot);
        if (slot == 40) return stackAt(snapshot.equipment(), 4);
        if (slot >= 100 && slot <= 103) return stackAt(snapshot.craft(), slot - 100);
        if (slot == MoveCompanionInventoryPayload.CRAFT_RESULT) return stackAt(snapshot.craft(), 4);
        if (slot >= MoveCompanionInventoryPayload.OWNER_OFFSET
                && slot <= MoveCompanionInventoryPayload.OWNER_LAST && minecraft.player != null) {
            return minecraft.player.getInventory().getItem(slot - MoveCompanionInventoryPayload.OWNER_OFFSET);
        }
        return ItemStack.EMPTY;
    }

    private boolean handleInventoryClick(int mouseX, int mouseY) {
        int slot = inventorySlotAt(mouseX, mouseY);
        if (slot < 0) return false;
        if (selectedInventorySlot < 0) {
            if (!inventoryStack(slot).isEmpty()) selectedInventorySlot = slot;
            return true;
        }
        if (selectedInventorySlot == slot) {
            selectedInventorySlot = -1;
            return true;
        }
        Services.NETWORK.sendToServer(MoveCompanionInventoryPayload.ID,
                new MoveCompanionInventoryPayload(uuid, selectedInventorySlot, slot));
        selectedInventorySlot = -1;
        return true;
    }

    */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !summoning && dismissPending == null && page == Page.CHAT
                && uuid != null && toggleToolFoldAt((int) mouseX, (int) mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean toggleToolFoldAt(int mouseX, int mouseY) {
        int top = 76;
        int bottom = height - 34;
        int totalW = width - 20;
        int statusW = totalW >= 430 ? 145 : totalW >= 340 ? 118 : 0;
        int transcriptW = totalW - (statusW == 0 ? 0 : statusW + 6);
        int left = 10;
        int viewTop = top + 5;
        int viewBottom = bottom - 5;
        if (mouseX < left + 4 || mouseX >= left + transcriptW - 5
                || mouseY < viewTop || mouseY >= viewBottom) return false;
        List<Row> rows = buildTranscriptRows(transcriptW - 12);
        int index = (mouseY - (viewTop - chatScroll)) / LINE_H;
        if (index < 0 || index >= rows.size()) return false;
        String key = rows.get(index).foldKey();
        if (key == null) return false;
        if (!expandedToolGroups.add(key)) expandedToolGroups.remove(key);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (page == Page.CHAT && uuid != null && delta != 0) {
            chatScroll = (int) Math.max(0, Math.min(lastMaxScroll,
                    (long) (chatScroll - delta * LINE_H * 3)));
            pinChatBottom = chatScroll >= lastMaxScroll;
            return true;
        }
        if (page == Page.SETTINGS && delta != 0 && settingsMaxScroll > 0) {
            preserveSettingsFields();
            settingsScroll = (int) Math.max(0, Math.min(settingsMaxScroll,
                    settingsScroll - delta * 28));
            discardSettingsWidgets();
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (summoning) {
            if (keyCode == 257 || keyCode == 335) {
                doSummon();
                return true;
            }
            if (keyCode == 256) {
                summoning = false;
                rebuild();
                return true;
            }
        }
        if (dismissPending != null && keyCode == 256) {
            dismissPending = null;
            rebuild();
            return true;
        }
        if (page == Page.CHAT && chatInput != null && chatInput.isFocused()
                && (keyCode == 257 || keyCode == 335)) {
            onSend();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (parent != null) minecraft.setScreen(parent);
        else super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record Row(FormattedCharSequence text, int color, List<String> toolIds, String foldKey) { }

    private record StatusLine(FormattedCharSequence text, int color) { }
}
