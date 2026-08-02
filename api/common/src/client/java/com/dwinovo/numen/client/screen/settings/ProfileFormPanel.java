package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.llm.LlmEndpoint;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.provider.LlmProvider;
import com.dwinovo.numen.agent.provider.ProviderRegistry;
import com.dwinovo.numen.client.agent.LlmErrorWords;
import com.dwinovo.numen.client.screen.ReasoningChoice;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Dropdown;
import com.dwinovo.numen.client.ui.widget.InlineAlert;
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
    private final Consumer<Draft> onSave;
    private final Runnable onCancel;

    private Draft draft = new Draft();
    private List<ProviderRegistry.Provider> sites = List.of();

    private TextField nameField, keyField, modelField, baseUrlField;
    private Dropdown sitePick, modelDropdown, thinkingSwitch, effortPick;
    private Button modelBackBtn;
    private Label thinkingLabel, effortLabel;
    private Button checkButton;
    private InlineAlert resultAlert;
    private volatile boolean checking;
    /** 模型行双态:预设下拉(含"自定义…") ↔ 自由输入(▾ 可回预设)。 */
    private boolean customModel;
    private List<String> siteModelIds = List.of();

    public ProfileFormPanel(Consumer<Draft> onSave, Runnable onCancel) {
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

    private int formW;

    public void build(int x, int y, int w, int h, int viewportBottom) {
        this.formW = w;
        ui.clear();
        ui.setViewportHeight(viewportBottom);

        sites = new ArrayList<>(ProviderRegistry.providers());
        if (draft.provider == null || draft.provider.isBlank()) {
            if (!sites.isEmpty()) adaptToSite(sites.get(0), false);
        }

        int ry = y;
        ry = label(x, ry, "numen.provider.form_name");
        nameField = ui.add(new TextField(draft.name, v -> draft.name = v));
        nameField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        ry = label(x, ry, "numen.provider.form_provider");
        List<String> siteNames = new ArrayList<>();
        for (ProviderRegistry.Provider p : sites) siteNames.add(p.name());
        sitePick = ui.add(new Dropdown(siteNames, indexOfSite(draft.provider), this::onSitePicked));
        sitePick.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        ry = label(x, ry, "numen.gui.settings.api_key");
        keyField = ui.add(new TextField(draft.apiKey, v -> draft.apiKey = v).masked(true));
        keyField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        // 模型行双态:有预设的站点默认整宽下拉(预设+自定义…);选"自定义…"
        // 切换成输入框,▾ 按钮随时回预设。无预设的站点(本地/自建)直接输入框。
        ry = label(x, ry, "numen.gui.settings.model");
        modelDropdown = ui.add(new Dropdown(List.of(), 0, this::onModelDropdownPicked));
        modelDropdown.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        modelField = ui.add(new TextField(draft.model, v -> draft.model = v));
        modelField.setBounds(x, ry, w - 17, NumenStyle.CONTROL_H);
        modelBackBtn = ui.add(new Button("▾", Button.Style.NORMAL, this::onModelBackToPresets));
        modelBackBtn.setBounds(x + w - 15, ry, 15, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        ry = label(x, ry, "numen.gui.settings.base_url");
        baseUrlField = ui.add(new TextField(draft.baseUrl, v -> draft.baseUrl = v));
        baseUrlField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        thinkingLabel = ui.add(new Label(t("numen.gui.providers.thinking"), Label.Role.MUTED));
        thinkingLabel.setBounds(x, ry, 70, 9);
        effortLabel = ui.add(new Label(t("numen.gui.providers.effort"), Label.Role.MUTED));
        effortLabel.setBounds(x + 86, ry, 80, 9);
        ry += NumenStyle.LABEL_PITCH;
        thinkingSwitch = ui.add(new Dropdown(List.of(
                t("numen.gui.providers.thinking.auto"),
                t("numen.gui.providers.thinking.on"),
                t("numen.gui.providers.thinking.off")), 0, this::onThinkingSwitched));
        thinkingSwitch.setBounds(x, ry, 78, NumenStyle.CONTROL_H);
        effortPick = ui.add(new Dropdown(List.of("low", "medium", "high"),
                ReasoningChoice.LEVEL_MEDIUM, this::onEffortPicked));
        effortPick.setBounds(x + 86, ry, 78, NumenStyle.CONTROL_H);

        // ✕ 在卡片右上角(通用关闭位);底行只留 [检测][保存]。
        Button close = ui.add(new Button("✕", Button.Style.NORMAL, onCancel));
        close.setBounds(x + w - 15, y - 1, 15, NumenStyle.CONTROL_H);

        int by = y + h - 16;
        // 检测结果驻留条:嵌在按钮行上方,不自动消失——修 key 时它一直看得见。
        resultAlert = ui.add(new InlineAlert());
        resultAlert.setBounds(x, ry + 2, w, by - ry - 6);
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
        return ly + NumenStyle.LABEL_PITCH;
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

        siteModelIds = new ArrayList<>();
        for (ProviderRegistry.Model m : site.models()) siteModelIds.add(m.id());
        // 编辑既有档案:存的模型不在预设里 → 以自定义态打开(旧表单同语义)。
        customModel = !siteModelIds.isEmpty() && draft.model != null
                && !draft.model.isBlank() && !siteModelIds.contains(draft.model);
        List<String> items = new ArrayList<>(siteModelIds);
        items.add(t("numen.settings.custom_model"));
        int sel = Math.max(0, siteModelIds.indexOf(draft.model));
        modelDropdown.setItems(items, customModel ? items.size() - 1 : sel);
        refreshModelRow();
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

    /** 双态显隐:预设态整宽下拉;自定义态输入框+回预设按钮;无预设站点纯输入框。 */
    private void refreshModelRow() {
        boolean hasPresets = !siteModelIds.isEmpty();
        modelDropdown.setVisible(hasPresets && !customModel);
        modelField.setVisible(!hasPresets || customModel);
        modelBackBtn.setVisible(hasPresets && customModel);
        modelField.setBounds(modelField.x(), modelField.y(),
                hasPresets && customModel ? formW - 17 : formW, NumenStyle.CONTROL_H);
    }

    private void onModelDropdownPicked(int index) {
        if (index >= siteModelIds.size()) {           // 末项 = 自定义…
            customModel = true;
            draft.model = "";
            modelField.setValue("");
            refreshModelRow();
            return;
        }
        draft.model = siteModelIds.get(index);
        modelField.setValue(draft.model);
    }

    private void onModelBackToPresets() {
        customModel = false;
        draft.model = siteModelIds.isEmpty() ? "" : siteModelIds.get(0);
        modelField.setValue(draft.model);
        List<String> items = new ArrayList<>(siteModelIds);
        items.add(t("numen.settings.custom_model"));
        modelDropdown.setItems(items, 0);
        refreshModelRow();
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
            nameField.setError(t("numen.gui.inline.required"));   // 校验错误内联在错误发生处
            return;
        }
        onSave.accept(draft);
    }

    /** 检测草稿当前值——存不存都能先试连。 */
    private void runConnectivityCheck() {
        if (checking) return;
        if (draft.apiKey == null || draft.apiKey.isBlank()) {
            keyField.setError(t("numen.gui.inline.need_key"));
            return;
        }
        checking = true;
        checkButton.setEnabled(false);
        checkButton.setLabel(t("numen.gui.providers.checking"));
        resultAlert.clear();
        LlmEndpoint ep = new LlmEndpoint(draft.provider, draft.model, draft.apiKey,
                draft.baseUrl, Services.CONFIG.getProxy(), "auto");
        NumenLlmClient.forEndpoint(ep)
                .chatStreaming(List.of(new ConvoState.Msg.User("ping")), List.of(), "", null)
                .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                    checking = false;
                    checkButton.setEnabled(true);
                    checkButton.setLabel(t("numen.gui.providers.check"));
                    if (error == null) {
                        resultAlert.show(InlineAlert.Severity.SUCCESS, t("numen.gui.providers.check.ok"));
                    } else {
                        resultAlert.show(InlineAlert.Severity.ERROR, LlmErrorWords.classify(error));
                    }
                }));
    }

    private static String t(String key) {
        return Component.translatable(key).getString();
    }
}
