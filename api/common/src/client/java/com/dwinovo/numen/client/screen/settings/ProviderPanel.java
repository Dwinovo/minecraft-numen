package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.llm.LlmEndpoint;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.provider.LlmProvider;
import com.dwinovo.numen.agent.provider.ProviderRegistry;
import com.dwinovo.numen.client.agent.LlmErrorWords;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.widget.Badge;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Dropdown;
import com.dwinovo.numen.client.ui.widget.InlineAlert;
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
 * 独立设置屏(/numen)的瓤。宿主只负责边界与重建时机、事件转发;
 * 校验错误内联在字段,操作结果走页面级 InlineAlert,均为面板自持。
 *
 * <p>布局自适应:左栏宽随总宽收缩(下限保名字可读),行距紧凑——G 面板
 * 尺寸对标原版 GUI,寸土寸金。
 */
import com.dwinovo.numen.data.ModLanguageData;

public final class ProviderPanel {

    private final UiRoot ui = new UiRoot();

    private List<ProviderRegistry.Provider> sites = List.of();
    private ListView<ProviderRegistry.Provider> siteList;
    private TextField keyField, baseUrlField, modelField, proxyField;
    private Dropdown modelPick, thinkingPick;
    private Label siteTitle, thinkingLabel;
    private boolean thinkingToggleOnly;
    private Button checkButton;
    private InlineAlert resultAlert;
    private volatile boolean checking;

    private int x, y, w, h, listW;

    public ProviderPanel() {
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
        // 文本编辑交给真 EditBox(只收事件、不自绘),画面仍归 NumenUI。
        // 这是输入法辅助模组能认出这些框的前提——见 McTextInput。
        ui.setInputFactory(com.dwinovo.numen.client.ui.mc.McTextInput.factory());
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
        keyField.setBounds(rx, ry, rw, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        ry = label(rx, ry, "numen.gui.settings.model");
        modelField = ui.add(new TextField(cfg.getModel(), v -> {
            cfg.setModel(v);
            cfg.save();
        }));
        modelField.setBounds(rx, ry, rw - 17, NumenStyle.CONTROL_H);
        modelPick = ui.add(new Dropdown(List.of(), 0, i -> onModelPicked()).compact().popupWidth(rw));
        modelPick.setBounds(rx + rw - 15, ry, 15, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        ry = label(rx, ry, "numen.gui.settings.base_url");
        baseUrlField = ui.add(new TextField(cfg.getBaseUrl(), v -> {
            cfg.setBaseUrl(v);
            cfg.save();
        }));
        baseUrlField.setBounds(rx, ry, rw, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        // 全局代理(档案留空时的回退,皮肤上传等非 LLM 流量也走它)。
        ry = label(rx, ry, ModLanguageData.Keys.GUI_PROVIDERS_PROXY_GLOBAL);
        proxyField = ui.add(new TextField(cfg.getProxy(), v -> {
            cfg.setProxy(v);
            cfg.save();
        }).placeholder("127.0.0.1:7890"));
        proxyField.setBounds(rx, ry, rw, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        // 单一自适应思考控件(主流形态):力度型站点出 自动/关闭/低/中/高,
        // 开关型出 自动/开启/关闭,常开型隐藏——后端两参数的复杂度不泄漏给用户。
        thinkingLabel = ui.add(new Label(t(ModLanguageData.Keys.GUI_PROVIDERS_THINKING), Label.Role.MUTED));
        thinkingLabel.setBounds(rx, ry, 100, 9);
        ry += NumenStyle.LABEL_PITCH;
        thinkingPick = ui.add(new Dropdown(List.of(), 0, this::onThinkingPicked));
        thinkingPick.setBounds(rx, ry, 110, NumenStyle.CONTROL_H);

        // 页面级 Alert:右栏左右居中、垂直偏上悬浮。
        resultAlert = ui.add(new InlineAlert());
        resultAlert.setBounds(rx, y + 2, rw, 24);
        checkButton = ui.add(new Button(t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK),
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
        return ly + NumenStyle.LABEL_PITCH;
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
            Badge.draw(s, t(ModLanguageData.Keys.GUI_PROVIDERS_BADGE_LOCAL), bx, rowY + 2, c.success(), 0xFFFFFFFF);
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
        thinkingPick.setVisible(!none);
        thinkingToggleOnly = toggleOnly;
        if (!none) {
            if (toggleOnly) {
                thinkingPick.setItems(List.of(
                        t(ModLanguageData.Keys.GUI_PROVIDERS_THINKING_AUTO),
                        t(ModLanguageData.Keys.GUI_PROVIDERS_THINKING_ON),
                        t(ModLanguageData.Keys.GUI_PROVIDERS_THINKING_OFF)),
                        switch (nz(stored)) {
                            case "off" -> 2;
                            case "low", "medium", "high" -> 1;
                            default -> 0;
                        });
            } else {
                thinkingPick.setItems(List.of(
                        t(ModLanguageData.Keys.GUI_PROVIDERS_THINKING_AUTO),
                        t(ModLanguageData.Keys.GUI_PROVIDERS_THINKING_OFF),
                        t(ModLanguageData.Keys.GUI_PROVIDERS_EFFORT_LOW),
                        t(ModLanguageData.Keys.GUI_PROVIDERS_EFFORT_MEDIUM),
                        t(ModLanguageData.Keys.GUI_PROVIDERS_EFFORT_HIGH)),
                        switch (nz(stored)) {
                            case "off" -> 1;
                            case "low" -> 2;
                            case "medium" -> 3;
                            case "high" -> 4;
                            default -> 0;
                        });
            }
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

    private void onThinkingPicked(int index) {
        INumenConfig cfg = Services.CONFIG;
        cfg.setReasoningEffort(ProfileFormPanel.thinkingValue(thinkingToggleOnly, index));
        cfg.save();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private void runConnectivityCheck() {
        if (checking) return;
        INumenConfig cfg = Services.CONFIG;
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            keyField.setError(t(ModLanguageData.Keys.GUI_INLINE_NEED_KEY));
            return;
        }
        checking = true;
        checkButton.setEnabled(false);
        checkButton.setLabel(t(ModLanguageData.Keys.GUI_PROVIDERS_CHECKING));
        resultAlert.show(InlineAlert.Severity.INFO, t(ModLanguageData.Keys.GUI_PROVIDERS_CHECKING));
        LlmEndpoint ep = new LlmEndpoint(cfg.getProvider(), cfg.getModel(), cfg.getApiKey(),
                cfg.getBaseUrl(), cfg.getProxy(), "auto");
        NumenLlmClient.forEndpoint(ep)
                .chatStreaming(List.of(new ConvoState.Msg.User("ping")), List.of(), "", null)
                .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                    checking = false;
                    checkButton.setEnabled(true);
                    checkButton.setLabel(t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK));
                    if (error == null) {
                        resultAlert.show(InlineAlert.Severity.SUCCESS,
                                t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK_OK), 2_500);
                    } else {
                        resultAlert.show(InlineAlert.Severity.ERROR, LlmErrorWords.classify(error));
                    }
                }));
    }

    private static String t(String key) {
        return Component.translatable(key).getString();
    }
}
