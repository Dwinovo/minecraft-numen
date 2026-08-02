package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.llm.LlmEndpoint;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.provider.LlmProvider;
import com.dwinovo.numen.agent.provider.ProviderRegistry;
import com.dwinovo.numen.client.agent.LlmErrorWords;
import com.dwinovo.numen.client.screen.ReasoningChoice;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.NumenToasts;
import com.dwinovo.numen.client.ui.widget.Badge;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Dropdown;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.ListView;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.platform.services.INumenConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 提供商配置面板(NumenUI 复合组件)——**宿主无关**:给一块矩形就能活,
 * 独立设置屏与 G 面板的"连接"分区共用同一个瓤。宿主负责:边界与重建时机、
 * 事件转发、toast 落点(注入 {@link ToastSink}——嵌在 G 里时 HUD toast 会被
 * 面板挡住,必须投宿主自己的实例)。
 *
 * <p>布局自适应:左栏宽随总宽收缩(下限保名字可读),行距紧凑——G 面板
 * 尺寸对标原版 GUI,寸土寸金。
 */
public final class ProviderPanel {

    /** toast 出口由宿主注入:各宿主画在自己的最上层。 */
    public interface ToastSink {
        void push(NumenToasts.Severity severity, String message);
    }

    private final UiRoot ui = new UiRoot();
    private final ToastSink toasts;

    private List<ProviderRegistry.Provider> sites = List.of();
    private ListView<ProviderRegistry.Provider> siteList;
    private TextField keyField, baseUrlField, modelField;
    private Dropdown modelPick, thinkingSwitch, effortPick;
    private Label siteTitle, thinkingLabel, effortLabel;
    private Button checkButton;
    private volatile boolean checking;

    private int x, y, w, h, listW;

    public ProviderPanel(ToastSink toasts) {
        this.toasts = toasts;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
    }

    /** (重)布局进给定矩形。viewportBottom = 下拉弹层不得越过的纵坐标(通常是宿主面板底缘)。 */
    public void build(int x, int y, int w, int h, int viewportBottom) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        ui.clear();
        ui.setViewportHeight(viewportBottom);

        INumenConfig cfg = Services.CONFIG;
        sites = new ArrayList<>(ProviderRegistry.providers());
        listW = Math.max(92, Math.min(130, w * 3 / 10));

        siteList = ui.add(new ListView<>(sites, 15, this::renderSiteRow, i -> onSiteSelected()));
        siteList.setBounds(x, y, listW, h);
        siteList.select(indexOfSite(cfg.getProvider()));

        int rx = x + listW + 8;
        int rw = w - listW - 8;
        int ry = y;

        siteTitle = ui.add(new Label("", Label.Role.PRIMARY));
        siteTitle.setBounds(rx, ry + 1, rw, 10);
        ry += 13;

        ry = label(rx, ry, "numen.gui.settings.api_key");
        keyField = ui.add(new TextField(cfg.getApiKey(), v -> {
            cfg.setApiKey(v);
            cfg.save();
        }).masked(true));
        keyField.setBounds(rx, ry, rw, 13);
        ry += 18;

        ry = label(rx, ry, "numen.gui.settings.model");
        modelField = ui.add(new TextField(cfg.getModel(), v -> {
            cfg.setModel(v);
            cfg.save();
        }));
        modelField.setBounds(rx, ry, rw - 17, 13);
        modelPick = ui.add(new Dropdown(List.of(), 0, i -> onModelPicked()).compact().popupWidth(rw));
        modelPick.setBounds(rx + rw - 15, ry, 15, 13);
        ry += 18;

        ry = label(rx, ry, "numen.gui.settings.base_url");
        baseUrlField = ui.add(new TextField(cfg.getBaseUrl(), v -> {
            cfg.setBaseUrl(v);
            cfg.save();
        }));
        baseUrlField.setBounds(rx, ry, rw, 13);
        ry += 18;

        thinkingLabel = ui.add(new Label(t("numen.gui.providers.thinking"), Label.Role.MUTED));
        thinkingLabel.setBounds(rx, ry, 70, 9);
        effortLabel = ui.add(new Label(t("numen.gui.providers.effort"), Label.Role.MUTED));
        effortLabel.setBounds(rx + 86, ry, 80, 9);
        ry += 10;
        thinkingSwitch = ui.add(new Dropdown(List.of(
                t("numen.gui.providers.thinking.auto"),
                t("numen.gui.providers.thinking.on"),
                t("numen.gui.providers.thinking.off")), 0, i -> onThinkingSwitched(i)));
        thinkingSwitch.setBounds(rx, ry, 78, 13);
        effortPick = ui.add(new Dropdown(List.of("low", "medium", "high"),
                ReasoningChoice.LEVEL_MEDIUM, i -> onEffortPicked(i)));
        effortPick.setBounds(rx + 86, ry, 78, 13);

        checkButton = ui.add(new Button(t("numen.gui.providers.check"),
                Button.Style.ACCENT, this::runConnectivityCheck));
        checkButton.setBounds(rx + rw - 54, y + h - 16, 54, 15);

        refreshDetail();
    }

    // ---- 宿主转发面 ----

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        s.fillRect(x + listW + 4, y, 1, h, c.divider());
        ui.render(s, c, mouseX, mouseY, nowMs);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        return ui.mouseClicked(mx, my, button);
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        return ui.mouseScrolled(mx, my, delta);
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        return ui.keyPressed(keyCode, modifiers);
    }

    public boolean charTyped(char ch) {
        return ui.charTyped(ch);
    }

    public boolean hasOverlay() {
        return ui.hasOverlay();
    }

    // ---- 内部逻辑(与独立屏时代一致) ----

    private int label(int lx, int ly, String key) {
        Label l = ui.add(new Label(t(key), Label.Role.MUTED));
        l.setBounds(lx, ly, 120, 9);
        return ly + 10;
    }

    private int indexOfSite(String providerId) {
        String canon = ProviderRegistry.canonicalId(providerId);
        for (int i = 0; i < sites.size(); i++) {
            if (sites.get(i).id().equals(canon)) return i;
        }
        return sites.isEmpty() ? -1 : 0;
    }

    private void renderSiteRow(IDrawSurface s, NumenTheme.Colors c,
                               ProviderRegistry.Provider site, int index,
                               int rowX, int rowY, int rowW, int rowH,
                               boolean selected, boolean hovered) {
        if (selected) {
            s.fillRect(rowX, rowY, rowW, rowH, c.selected());
            s.fillRect(rowX, rowY, 2, rowH, c.accent());
        } else if (hovered) {
            s.fillRect(rowX, rowY, rowW, rowH, c.hover());
        }
        s.drawText(site.name(), rowX + 6, rowY + 3, c.textPrimary(), false);
        int bx = rowX + 10 + s.textWidth(site.name());
        if ("anthropic".equals(site.protocol())) {
            bx += Badge.draw(s, "A", bx, rowY + 2, c.accent(), 0xFFFFFFFF) + 2;
        }
        if (site.baseUrl().startsWith("http://localhost")) {
            Badge.draw(s, t("numen.gui.providers.badge.local"), bx, rowY + 2, c.success(), 0xFFFFFFFF);
        }
    }

    private void onSiteSelected() {
        int idx = siteList.selectedIndex();
        if (idx < 0) return;
        INumenConfig cfg = Services.CONFIG;
        cfg.setProvider(sites.get(idx).id());
        cfg.save();
        refreshDetail();
    }

    private void refreshDetail() {
        int idx = siteList.selectedIndex();
        if (idx < 0 || idx >= sites.size()) return;
        ProviderRegistry.Provider site = sites.get(idx);
        siteTitle.setText(site.name());

        List<String> modelIds = new ArrayList<>();
        for (ProviderRegistry.Model m : site.models()) modelIds.add(m.id());
        modelPick.setItems(modelIds, 0);
        modelPick.setEnabled(!modelIds.isEmpty());
        baseUrlField.placeholder(site.baseUrl());

        String format = effectiveThinkingFormat(site);
        String stored = Services.CONFIG.getReasoningEffort();
        boolean none = LlmProvider.THINKING_NONE.equals(format);
        boolean toggleOnly = LlmProvider.THINKING_TYPE.equals(format)
                || LlmProvider.THINKING_ENABLE_BOOL.equals(format);
        thinkingLabel.setVisible(!none);
        thinkingSwitch.setVisible(!none);
        effortLabel.setVisible(!none && !toggleOnly);
        effortPick.setVisible(!none && !toggleOnly);
        if (!none) {
            thinkingSwitch.setItems(List.of(
                    t("numen.gui.providers.thinking.auto"),
                    t("numen.gui.providers.thinking.on"),
                    t("numen.gui.providers.thinking.off")),
                    ReasoningChoice.switchIndex(stored));
            effortPick.setItems(List.of("low", "medium", "high"),
                    ReasoningChoice.levelIndex(stored));
            effortPick.setEnabled(ReasoningChoice.switchIndex(stored) == ReasoningChoice.SWITCH_ON);
        }
    }

    static String effectiveThinkingFormat(ProviderRegistry.Provider site) {
        if (!site.thinkingFormat().isBlank()) return site.thinkingFormat();
        LlmProvider p = NumenLlmClient.pickProvider(site.id());
        if (p instanceof com.dwinovo.numen.agent.provider.OpenAIProvider oai) return oai.thinkingFormat();
        if (p instanceof com.dwinovo.numen.agent.provider.AnthropicProvider a) return a.thinkingFormat();
        return LlmProvider.THINKING_EFFORT;
    }

    private void onModelPicked() {
        String id = modelPick.selectedItem();
        modelField.setValue(id);
        INumenConfig cfg = Services.CONFIG;
        cfg.setModel(id);
        cfg.save();
    }

    private void onThinkingSwitched(int switchIdx) {
        saveReasoning(switchIdx, effortPick.selectedIndex());
        effortPick.setEnabled(switchIdx == ReasoningChoice.SWITCH_ON);
    }

    private void onEffortPicked(int levelIdx) {
        saveReasoning(thinkingSwitch.selectedIndex(), levelIdx);
    }

    private void saveReasoning(int switchIdx, int levelIdx) {
        INumenConfig cfg = Services.CONFIG;
        cfg.setReasoningEffort(ReasoningChoice.compose(switchIdx, levelIdx));
        cfg.save();
    }

    private void runConnectivityCheck() {
        if (checking) return;
        INumenConfig cfg = Services.CONFIG;
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            toasts.push(NumenToasts.Severity.WARN, t("numen.gui.providers.check.no_key"));
            return;
        }
        checking = true;
        checkButton.setEnabled(false);
        toasts.push(NumenToasts.Severity.INFO, t("numen.gui.providers.check.running"));
        LlmEndpoint ep = new LlmEndpoint(cfg.getProvider(), cfg.getModel(), cfg.getApiKey(),
                cfg.getBaseUrl(), cfg.getProxy(), "auto");
        NumenLlmClient.forEndpoint(ep)
                .chatStreaming(List.of(new ConvoState.Msg.User("ping")), List.of(), "", null)
                .whenComplete((result, error) -> {
                    checking = false;
                    checkButton.setEnabled(true);
                    if (error == null) {
                        toasts.push(NumenToasts.Severity.INFO, t("numen.gui.providers.check.ok"));
                    } else {
                        toasts.push(NumenToasts.Severity.ERROR, LlmErrorWords.classify(error));
                    }
                });
    }

    private static String t(String key) {
        return Component.translatable(key).getString();
    }
}
