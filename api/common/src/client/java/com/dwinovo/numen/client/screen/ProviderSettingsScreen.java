package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.llm.LlmEndpoint;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.http.LlmHttpException;
import com.dwinovo.numen.agent.provider.LlmProvider;
import com.dwinovo.numen.agent.provider.ProviderRegistry;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.NumenToasts;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.widget.Badge;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Dropdown;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.ListView;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.platform.services.INumenConfig;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 提供商设置(NumenUI 首个分区):左栏站点列表 + 右栏该站详情。
 * 屏幕层只做组装与 MC 事件转发——交互逻辑全在 UiRoot 以下的纯 JVM 层。
 *
 * <h2>数据绑定(v1 = 全局单活跃语义)</h2>
 * 沿用现行配置模型:全局一份 provider/key/model/baseUrl,选中站点即切换
 * 全局 provider,右栏编辑即时写入 config。每站独立记 key 的"档案"形态
 * 属同伴分区(ProviderLibrary)那一轮。temperature/maxTokens 的图形编辑
 * 待注册表写接口,一并后置。
 */
public final class ProviderSettingsScreen extends Screen {

    private final Screen parent;
    private final UiRoot ui = new UiRoot();
    private final NumenToasts toasts = new NumenToasts();
    private final NumenTheme.Colors colors = NumenTheme.DARK.colors();

    private List<ProviderRegistry.Provider> sites = List.of();
    private ListView<ProviderRegistry.Provider> siteList;
    private TextField keyField, baseUrlField, modelField;
    private Dropdown modelPick, thinkingPick;
    private Label siteTitle;
    private Button checkButton;

    /** 检测请求在飞时禁再点(结果回来经 toast,线程安全)。 */
    private volatile boolean checking;

    private int panelX, panelY, panelW, panelH, listW;

    public ProviderSettingsScreen(Screen parent) {
        super(Component.translatable("numen.gui.providers.title"));
        this.parent = parent;
    }

    public static void open(Screen parent) {
        net.minecraft.client.Minecraft.getInstance().setScreen(new ProviderSettingsScreen(parent));
    }

    @Override
    protected void init() {
        ui.clear();
        ui.setClipboard(() -> minecraft.keyboardHandler.getClipboard(),
                s -> minecraft.keyboardHandler.setClipboard(s));

        panelW = Math.min(420, width - 20);
        panelH = Math.min(230, height - 20);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        listW = 120;

        INumenConfig cfg = Services.CONFIG;
        sites = new ArrayList<>(ProviderRegistry.providers());

        // ---- 左栏:站点列表 ----
        siteList = ui.add(new ListView<>(sites, 16, this::renderSiteRow, this::onSiteSelected));
        siteList.setBounds(panelX, panelY + 18, listW, panelH - 18);
        int current = indexOfSite(cfg.getProvider());
        siteList.select(current);

        // ---- 右栏:详情 ----
        int rx = panelX + listW + 10;
        int rw = panelW - listW - 20;
        int y = panelY + 18;

        siteTitle = ui.add(new Label("", Label.Role.PRIMARY));
        siteTitle.setBounds(rx, y + 2, rw, 10);
        y += 16;

        y = label(rx, y, "numen.gui.settings.api_key");
        keyField = ui.add(new TextField(cfg.getApiKey(), v -> {
            cfg.setApiKey(v);
            cfg.save();
        }).masked(true));
        keyField.setBounds(rx, y, rw, 14);
        y += 20;

        y = label(rx, y, "numen.gui.settings.model");
        modelField = ui.add(new TextField(cfg.getModel(), v -> {
            cfg.setModel(v);
            cfg.save();
        }));
        modelField.setBounds(rx, y, rw - 18, 14);
        modelPick = ui.add(new Dropdown(List.of(), 0, this::onModelPicked)
                .compact().popupWidth(rw));
        modelPick.setBounds(rx + rw - 16, y, 16, 14);
        y += 20;

        y = label(rx, y, "numen.gui.settings.base_url");
        baseUrlField = ui.add(new TextField(cfg.getBaseUrl(), v -> {
            cfg.setBaseUrl(v);
            cfg.save();
        }));
        baseUrlField.setBounds(rx, y, rw, 14);
        y += 20;

        y = label(rx, y, "numen.gui.providers.thinking");
        thinkingPick = ui.add(new Dropdown(List.of(), 0, this::onThinkingPicked));
        thinkingPick.setBounds(rx, y, 110, 14);

        checkButton = ui.add(new Button(
                Component.translatable("numen.gui.providers.check").getString(),
                Button.Style.ACCENT, this::runConnectivityCheck));
        checkButton.setBounds(rx + rw - 60, panelY + panelH - 24, 60, 16);

        Button back = ui.add(new Button(
                Component.translatable("gui.back").getString(),
                Button.Style.NORMAL, this::onClose));
        back.setBounds(rx, panelY + panelH - 24, 50, 16);

        refreshDetail();
    }

    private int label(int x, int y, String key) {
        Label l = ui.add(new Label(Component.translatable(key).getString(), Label.Role.MUTED));
        l.setBounds(x, y, 100, 9);
        return y + 10;
    }

    private int indexOfSite(String providerId) {
        String canon = ProviderRegistry.canonicalId(providerId);
        for (int i = 0; i < sites.size(); i++) {
            if (sites.get(i).id().equals(canon)) return i;
        }
        return sites.isEmpty() ? -1 : 0;
    }

    private void renderSiteRow(com.dwinovo.numen.client.ui.IDrawSurface s, NumenTheme.Colors c,
                               ProviderRegistry.Provider site, int index,
                               int rowX, int rowY, int rowW, int rowH,
                               boolean selected, boolean hovered) {
        if (selected) s.fillRect(rowX, rowY, rowW, rowH, c.selected());
        else if (hovered) s.fillRect(rowX, rowY, rowW, rowH, c.hover());
        s.drawText(site.name(), rowX + 4, rowY + 4, c.textPrimary(), false);
        int bx = rowX + 8 + s.textWidth(site.name());
        if ("anthropic".equals(site.protocol())) bx += Badge.draw(s, c, "A", bx, rowY + 3) + 2;
        if (site.baseUrl().startsWith("http://localhost")) Badge.draw(s, c, t("numen.gui.providers.badge.local"), bx, rowY + 3);
    }

    private void onSiteSelected(int index) {
        ProviderRegistry.Provider site = sites.get(index);
        INumenConfig cfg = Services.CONFIG;
        cfg.setProvider(site.id());
        cfg.save();
        refreshDetail();
    }

    /** 右栏随选中站点刷新:模型候选、基址占位、思考控件按方言换形态。 */
    private void refreshDetail() {
        int idx = siteList.selectedIndex();
        if (idx < 0 || idx >= sites.size()) return;
        ProviderRegistry.Provider site = sites.get(idx);
        siteTitle.setText(site.name());

        List<String> modelIds = new ArrayList<>();
        for (ProviderRegistry.Model m : site.models()) modelIds.add(m.id());
        modelPick.setItems(modelIds.isEmpty() ? List.of() : modelIds, 0);
        modelPick.setEnabled(!modelIds.isEmpty());

        baseUrlField.placeholder(site.baseUrl());

        // 思考控件按方言换形态:effort 系出力度档,开关系出三态,none 隐藏。
        String format = effectiveThinkingFormat(site);
        INumenConfig cfg = Services.CONFIG;
        String stored = cfg.getReasoningEffort() == null ? "auto" : cfg.getReasoningEffort();
        switch (format) {
            case LlmProvider.THINKING_NONE -> thinkingPick.setVisible(false);
            case LlmProvider.THINKING_TYPE, LlmProvider.THINKING_ENABLE_BOOL -> {
                thinkingPick.setVisible(true);
                thinkingPick.setItems(List.of(
                        t("numen.gui.providers.thinking.auto"),
                        t("numen.gui.providers.thinking.on"),
                        t("numen.gui.providers.thinking.off")),
                        "off".equals(stored) ? 2 : isEffortOn(stored) ? 1 : 0);
            }
            default -> {   // effort / effort-nested / budget:力度档
                thinkingPick.setVisible(true);
                List<String> levels = List.of(
                        t("numen.gui.providers.thinking.auto"),
                        t("numen.gui.providers.thinking.off"),
                        "low", "medium", "high");
                int sel = switch (stored) {
                    case "off" -> 1;
                    case "low" -> 2;
                    case "medium" -> 3;
                    case "high" -> 4;
                    default -> 0;
                };
                thinkingPick.setItems(levels, sel);
            }
        }
    }

    private static boolean isEffortOn(String stored) {
        return "low".equals(stored) || "medium".equals(stored) || "high".equals(stored);
    }

    /** 站点行的方言;有子类的站点(deepseek/moonshot)行内未配时问装配后的 provider。 */
    private static String effectiveThinkingFormat(ProviderRegistry.Provider site) {
        if (!site.thinkingFormat().isBlank()) return site.thinkingFormat();
        LlmProvider p = NumenLlmClient.pickProvider(site.id());
        if (p instanceof com.dwinovo.numen.agent.provider.OpenAIProvider oai) return oai.thinkingFormat();
        if (p instanceof com.dwinovo.numen.agent.provider.AnthropicProvider a) return a.thinkingFormat();
        return LlmProvider.THINKING_EFFORT;
    }

    private void onModelPicked(int index) {
        String id = modelPick.selectedItem();
        modelField.setValue(id);
        INumenConfig cfg = Services.CONFIG;
        cfg.setModel(id);
        cfg.save();
    }

    private void onThinkingPicked(int index) {
        int idx = siteList.selectedIndex();
        if (idx < 0) return;
        String format = effectiveThinkingFormat(sites.get(idx));
        String value = switch (format) {
            case LlmProvider.THINKING_TYPE, LlmProvider.THINKING_ENABLE_BOOL ->
                    index == 2 ? "off" : index == 1 ? "medium" : "auto";   // 开关系:开=medium 档
            default -> switch (index) {
                case 1 -> "off";
                case 2 -> "low";
                case 3 -> "medium";
                case 4 -> "high";
                default -> "auto";
            };
        };
        INumenConfig cfg = Services.CONFIG;
        cfg.setReasoningEffort(value);
        cfg.save();
    }

    // ---- 检测:真发一次最小请求,分类话术进 toast ----

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
                        toasts.push(NumenToasts.Severity.ERROR, classify(error));
                    }
                });
    }

    /** 分类话术:说人话 + 说下一步;堆栈归日志(传输层已经记了)。 */
    private static String classify(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && !(cause instanceof LlmHttpException)) cause = cause.getCause();
        if (cause instanceof LlmHttpException http) {
            if (http.isUnauthorized()) return t("numen.gui.providers.check.unauthorized");
            if (http.statusCode() == 404) return t("numen.gui.providers.check.not_found");
            if (http.isRateLimited()) return t("numen.gui.providers.check.rate_limited");
            if (http.statusCode() >= 500) return t("numen.gui.providers.check.server_error");
            return t("numen.gui.providers.check.bad_request") + " (HTTP " + http.statusCode() + ")";
        }
        return t("numen.gui.providers.check.network");
    }

    private static String t(String key) {
        return Component.translatable(key).getString();
    }

    // ---- MC 屏幕接线:原样转发 ----

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        McDrawSurface s = new McDrawSurface(g, font);
        s.fillRoundRect(panelX - 6, panelY - 6, panelW + 12, panelH + 12, 4, colors.panelBg());
        s.drawText(title.getString(), panelX, panelY + 2, colors.textPrimary(), false);
        s.fillRect(panelX + listW + 4, panelY + 18, 1, panelH - 18, colors.divider());
        ui.render(s, colors, mouseX, mouseY, Util.getMillis());
        toasts.render(s, width, colors, Util.getMillis());
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (ui.mouseClicked(mx, my, button)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return ui.mouseReleased(mx, my, button) || super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return ui.mouseDragged(mx, my, dx, dy) || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        return ui.mouseScrolled(mx, my, scrollY) || super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ui.keyPressed(keyCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char ch, int modifiers) {
        return ui.charTyped(ch) || super.charTyped(ch, modifiers);
    }

    @Override
    public void onClose() {
        NumenLlmClient.reset();   // 设置变更后,后续请求按新配置重建客户端
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
