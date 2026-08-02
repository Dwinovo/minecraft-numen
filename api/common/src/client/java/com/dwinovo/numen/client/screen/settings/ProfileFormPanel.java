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
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Dropdown;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 模型配置(档案)的编辑表单——NumenUI 版的瓤,替换旧表单缺的全套:
 * 检测按钮、思考开关、推理强度、toast、分类报错。档案思想不变:多份具名
 * 配置,创建同伴时自选。编辑的是 {@link Draft} 草稿,保存才落库(与旧表单
 * 同语义);检测直接测草稿当前值,存不存都能先试。
 */
public final class ProfileFormPanel {

    /** 表单草稿:与 ProviderLibrary.Entry 的字段一一对应(id 由宿主管理)。 */
    public static final class Draft {
        public String name = "";
        public String provider = "";
        public String model = "";
        public String apiKey = "";
        public String baseUrl = "";
        public String reasoningEffort = "";
    }

    private final UiRoot ui = new UiRoot();
    private final ProviderPanel.ToastSink toasts;
    private final Consumer<Draft> onSave;
    private final Runnable onCancel;

    private Draft draft = new Draft();
    private List<ProviderRegistry.Provider> sites = List.of();

    private TextField nameField, keyField, modelField, baseUrlField;
    private Dropdown sitePick, modelPick, thinkingSwitch, effortPick;
    private Label thinkingLabel, effortLabel;
    private Button checkButton;
    private volatile boolean checking;

    public ProfileFormPanel(ProviderPanel.ToastSink toasts, Consumer<Draft> onSave, Runnable onCancel) {
        this.toasts = toasts;
        this.onSave = onSave;
        this.onCancel = onCancel;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
    }

    /** 载入待编辑的草稿(新建=空草稿;编辑=从条目拷来)。宿主随后 build。 */
    public void open(Draft d) {
        this.draft = d;
    }

    public void build(int x, int y, int w, int h, int viewportBottom) {
        ui.clear();
        ui.setViewportHeight(viewportBottom);

        sites = new ArrayList<>(ProviderRegistry.providers());
        if (draft.provider == null || draft.provider.isBlank()) {
            if (!sites.isEmpty()) adaptToSite(sites.get(0), false);
        }

        int ry = y;
        ry = label(x, ry, "numen.provider.form_name");
        nameField = ui.add(new TextField(draft.name, v -> draft.name = v));
        nameField.setBounds(x, ry, w, 13);
        ry += 18;

        ry = label(x, ry, "numen.provider.form_provider");
        List<String> siteNames = new ArrayList<>();
        for (ProviderRegistry.Provider p : sites) siteNames.add(p.name());
        sitePick = ui.add(new Dropdown(siteNames, indexOfSite(draft.provider), this::onSitePicked));
        sitePick.setBounds(x, ry, w, 13);
        ry += 18;

        ry = label(x, ry, "numen.gui.settings.api_key");
        keyField = ui.add(new TextField(draft.apiKey, v -> draft.apiKey = v).masked(true));
        keyField.setBounds(x, ry, w, 13);
        ry += 18;

        ry = label(x, ry, "numen.gui.settings.model");
        modelField = ui.add(new TextField(draft.model, v -> draft.model = v));
        modelField.setBounds(x, ry, w - 17, 13);
        modelPick = ui.add(new Dropdown(List.of(), 0, i -> onModelPicked()).compact().popupWidth(w));
        modelPick.setBounds(x + w - 15, ry, 15, 13);
        ry += 18;

        ry = label(x, ry, "numen.gui.settings.base_url");
        baseUrlField = ui.add(new TextField(draft.baseUrl, v -> draft.baseUrl = v));
        baseUrlField.setBounds(x, ry, w, 13);
        ry += 18;

        thinkingLabel = ui.add(new Label(t("numen.gui.providers.thinking"), Label.Role.MUTED));
        thinkingLabel.setBounds(x, ry, 70, 9);
        effortLabel = ui.add(new Label(t("numen.gui.providers.effort"), Label.Role.MUTED));
        effortLabel.setBounds(x + 86, ry, 80, 9);
        ry += 10;
        thinkingSwitch = ui.add(new Dropdown(List.of(
                t("numen.gui.providers.thinking.auto"),
                t("numen.gui.providers.thinking.on"),
                t("numen.gui.providers.thinking.off")), 0, this::onThinkingSwitched));
        thinkingSwitch.setBounds(x, ry, 78, 13);
        effortPick = ui.add(new Dropdown(List.of("low", "medium", "high"),
                ReasoningChoice.LEVEL_MEDIUM, this::onEffortPicked));
        effortPick.setBounds(x + 86, ry, 78, 13);

        int by = y + h - 16;
        Button cancel = ui.add(new Button("✕", Button.Style.NORMAL, onCancel));
        cancel.setBounds(x, by, 18, 15);
        checkButton = ui.add(new Button(t("numen.gui.providers.check"),
                Button.Style.NORMAL, this::runConnectivityCheck));
        checkButton.setBounds(x + w - 54 - 58, by, 54, 15);
        Button save = ui.add(new Button(t("numen.gui.settings.save"),
                Button.Style.ACCENT, this::save));
        save.setBounds(x + w - 54, by, 54, 15);

        refreshSiteDependent();
    }

    // ---- 宿主转发面(与 ProviderPanel 同款) ----

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
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

    // ---- 内部 ----

    private int label(int lx, int ly, String key) {
        Label l = ui.add(new Label(t(key), Label.Role.MUTED));
        l.setBounds(lx, ly, 140, 9);
        return ly + 10;
    }

    private int indexOfSite(String providerId) {
        String canon = ProviderRegistry.canonicalId(providerId);
        for (int i = 0; i < sites.size(); i++) {
            if (sites.get(i).id().equals(canon)) return i;
        }
        return sites.isEmpty() ? -1 : 0;
    }

    private ProviderRegistry.Provider selectedSite() {
        int idx = sitePick == null ? indexOfSite(draft.provider) : sitePick.selectedIndex();
        return idx >= 0 && idx < sites.size() ? sites.get(idx) : null;
    }

    private void onSitePicked(int index) {
        if (index < 0 || index >= sites.size()) return;
        adaptToSite(sites.get(index), true);
        refreshSiteDependent();
    }

    /** 站点变了:档案跟着换成该站默认模型;基址清空回站点默认(占位符提示)。 */
    private void adaptToSite(ProviderRegistry.Provider site, boolean resetModel) {
        draft.provider = site.id();
        if (resetModel || draft.model == null || draft.model.isBlank()) {
            draft.model = site.models().isEmpty() ? "" : site.models().get(0).id();
            if (modelField != null) modelField.setValue(draft.model);
        }
        draft.baseUrl = "";
        if (baseUrlField != null) baseUrlField.setValue("");
    }

    /** 站点相关的联动刷新:模型候选、基址占位、思考控件按方言换形态。 */
    private void refreshSiteDependent() {
        ProviderRegistry.Provider site = selectedSite();
        if (site == null) return;

        List<String> modelIds = new ArrayList<>();
        for (ProviderRegistry.Model m : site.models()) modelIds.add(m.id());
        modelPick.setItems(modelIds, 0);
        modelPick.setEnabled(!modelIds.isEmpty());
        baseUrlField.placeholder(site.baseUrl());

        String format = ProviderPanel.effectiveThinkingFormat(site);
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
                    ReasoningChoice.switchIndex(draft.reasoningEffort));
            effortPick.setItems(List.of("low", "medium", "high"),
                    ReasoningChoice.levelIndex(draft.reasoningEffort));
            effortPick.setEnabled(
                    ReasoningChoice.switchIndex(draft.reasoningEffort) == ReasoningChoice.SWITCH_ON);
        }
    }

    private void onModelPicked() {
        draft.model = modelPick.selectedItem();
        modelField.setValue(draft.model);
    }

    private void onThinkingSwitched(int switchIdx) {
        draft.reasoningEffort = ReasoningChoice.compose(switchIdx, effortPick.selectedIndex());
        effortPick.setEnabled(switchIdx == ReasoningChoice.SWITCH_ON);
    }

    private void onEffortPicked(int levelIdx) {
        draft.reasoningEffort = ReasoningChoice.compose(thinkingSwitch.selectedIndex(), levelIdx);
    }

    private void save() {
        if (draft.name == null || draft.name.isBlank()) {
            toasts.push(NumenToasts.Severity.WARN, t("numen.provider.form.need_name"));
            return;
        }
        onSave.accept(draft);
    }

    /** 检测草稿当前值——存不存都能先试连。 */
    private void runConnectivityCheck() {
        if (checking) return;
        if (draft.apiKey == null || draft.apiKey.isBlank()) {
            toasts.push(NumenToasts.Severity.WARN, t("numen.gui.providers.check.no_key"));
            return;
        }
        checking = true;
        checkButton.setEnabled(false);
        toasts.push(NumenToasts.Severity.INFO, t("numen.gui.providers.check.running"));
        LlmEndpoint ep = new LlmEndpoint(draft.provider, draft.model, draft.apiKey,
                draft.baseUrl, Services.CONFIG.getProxy(), "auto");
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
