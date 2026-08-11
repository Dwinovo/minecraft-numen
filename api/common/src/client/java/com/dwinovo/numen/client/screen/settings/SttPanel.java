package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.client.stt.MicrophoneManager;
import com.dwinovo.numen.client.stt.SttProviders;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Dropdown;
import com.dwinovo.numen.client.ui.widget.InlineAlert;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.platform.services.INumenConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语音输入(STT)分区——NumenUI 版的瓤:服务商下拉联动(换商预填模型/基址)、
 * 掩码 Key、模型双态(预设下拉+自定义…↔输入框+▾ 回预设,与模型配置表单
 * 同款)、麦克风下拉、保存落配置并弹"已保存"回执。无列表无模态,
 * 直接躺在分区里;进分区时从配置重播种。
 */
public final class SttPanel {

    private final UiRoot ui = new UiRoot();

    private String provider = "";
    private String key = "";
    private String baseUrl = "";
    private String model = "";
    private String mic = "";
    private boolean customModel;
    private boolean seeded;

    private TextField keyField, modelField, baseUrlField;
    private Dropdown modelPick;
    private Button modelBackBtn;
    private InlineAlert saved;
    private List<String> providerIds = List.of();
    private List<String> modelIds = List.of();
    private List<String> micIds = List.of();
    private int panelX, panelY, panelW, panelH;

    public SttPanel() {
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
    }

    /** 切进分区时调用:下次 build 从已保存配置重播种(放弃未保存的改动)。 */
    public void reseed() {
        seeded = false;
    }

    public void build(int x, int y, int w, int h) {
        this.panelX = x;
        this.panelY = y;
        this.panelW = w;
        this.panelH = h;
        INumenConfig cfg = Services.CONFIG;
        if (!seeded) {
            seeded = true;
            provider = cfg.getSttProvider();
            key = cfg.getSttApiKey();
            baseUrl = cfg.getSttBaseUrl();
            model = cfg.getSttModel();
            mic = cfg.getSttMicrophone() == null ? "" : cfg.getSttMicrophone();
            SttProviders.Option seed = SttProviders.byId(provider);
            customModel = seed.models().isEmpty() || !seed.models().contains(model);
        }
        ui.clear();
        SttProviders.Option opt = SttProviders.byId(provider);
        provider = opt.id();

        Label title = ui.add(new Label(t(ModLanguageData.Keys.STT_TITLE), Label.Role.PRIMARY));
        title.setBounds(x, y, w, 9);

        int ry = y + 16;
        ry = label(x, ry, ModLanguageData.Keys.GUI_SETTINGS_PROVIDER);
        providerIds = new ArrayList<>();
        List<String> providerNames = new ArrayList<>();
        for (SttProviders.Option o : SttProviders.all()) {
            providerIds.add(o.id());
            providerNames.add(o.displayName());
        }
        Dropdown providerPick = ui.add(new Dropdown(providerNames,
                Math.max(0, providerIds.indexOf(provider)), this::onProviderPicked));
        providerPick.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        ry = label(x, ry, ModLanguageData.Keys.GUI_SETTINGS_API_KEY);
        keyField = ui.add(new TextField(key, v -> key = v).masked(true)
                .placeholder(SttProviders.BACKEND_DOUBAO.equals(opt.backend())
                        ? "API Key(旧版控制台填 appid:access_token)" : ""));
        keyField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        // 模型双态:预设下拉(+自定义…) ↔ 输入框(+▾ 回预设);无预设纯输入框。
        ry = label(x, ry, ModLanguageData.Keys.GUI_SETTINGS_MODEL);
        modelIds = new ArrayList<>(opt.models());
        boolean hasPresets = !modelIds.isEmpty();
        if (hasPresets && !customModel) {
            List<String> items = new ArrayList<>(modelIds);
            items.add(t("numen.settings.custom_model"));
            int sel = Math.max(0, modelIds.indexOf(model));
            modelPick = ui.add(new Dropdown(items, sel, this::onModelPicked));
            modelPick.setBounds(x, ry, w, NumenStyle.CONTROL_H);
            model = modelIds.get(sel);
        } else {
            modelField = ui.add(new TextField(model, v -> model = v));
            modelField.setBounds(x, ry, hasPresets ? w - 17 : w, NumenStyle.CONTROL_H);
            if (hasPresets) {
                modelBackBtn = ui.add(new Button("▾", Button.Style.NORMAL, this::onModelBackToPresets));
                modelBackBtn.setBounds(x + w - 15, ry, 15, NumenStyle.CONTROL_H);
            }
        }
        ry += NumenStyle.ROW_PITCH;

        ry = label(x, ry, ModLanguageData.Keys.GUI_SETTINGS_BASE_URL);
        baseUrlField = ui.add(new TextField(baseUrl, v -> baseUrl = v)
                .placeholder(opt.defaultBaseUrl()));
        baseUrlField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        ry = label(x, ry, ModLanguageData.Keys.STT_MICROPHONE);
        micIds = new ArrayList<>();
        List<String> micNames = new ArrayList<>();
        micIds.add("");
        micNames.add(t(ModLanguageData.Keys.STT_MIC_DEFAULT));
        for (String name : MicrophoneManager.deviceNames()) {
            micIds.add(name);
            micNames.add(name);
        }
        Dropdown micPick = ui.add(new Dropdown(micNames,
                Math.max(0, micIds.indexOf(mic)),
                i -> mic = micIds.get(Math.clamp(i, 0, micIds.size() - 1))));
        micPick.setBounds(x, ry, w, NumenStyle.CONTROL_H);

        saved = ui.add(new InlineAlert());
        saved.setBounds(x, y + 16, w, 24);
        Button save = ui.add(new Button(t("numen.gui.settings.save"),
                Button.Style.ACCENT, this::save));
        save.setBounds(x + w - 54, y + h - 16, 54, 15);
    }

    // ---- 宿主转发面 ----

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

    // ---- 内部 ----

    private int label(int lx, int ly, String labelKey) {
        Label l = ui.add(new Label(t(labelKey), Label.Role.MUTED));
        l.setBounds(lx, ly, 140, 9);
        return ly + NumenStyle.LABEL_PITCH;
    }

    /** 换服务商:模型/基址跟着换成该商预设(仍可改);自定义商直接自由输入。 */
    private void onProviderPicked(int index) {
        String id = providerIds.get(Math.clamp(index, 0, providerIds.size() - 1));
        if (id.equals(provider)) return;
        provider = id;
        SttProviders.Option o = SttProviders.byId(id);
        customModel = o.models().isEmpty();
        model = o.defaultModel();
        baseUrl = o.defaultBaseUrl();
        rebuild();
    }

    private void onModelPicked(int index) {
        if (index >= modelIds.size()) {   // 末项 = 自定义…
            customModel = true;
            model = "";
            rebuild();
            return;
        }
        model = modelIds.get(index);
    }

    private void onModelBackToPresets() {
        customModel = false;
        model = modelIds.isEmpty() ? "" : modelIds.get(0);
        rebuild();
    }

    /** 行随状态换(换商/模型双态切换):纯内部重建,不惊动宿主。 */
    private void rebuild() {
        build(panelX, panelY, panelW, panelH);
    }

    private void save() {
        INumenConfig cfg = Services.CONFIG;
        cfg.setSttProvider(provider);
        cfg.setSttApiKey(key.trim());
        cfg.setSttModel(model.trim());
        cfg.setSttBaseUrl(baseUrl.trim());
        cfg.setSttMicrophone(mic);
        cfg.save();
        saved.show(InlineAlert.Severity.SUCCESS, t("numen.gui.settings.saved"), 2_500);
    }

    private static String t(String labelKey) {
        return Component.translatable(labelKey).getString();
    }
}
