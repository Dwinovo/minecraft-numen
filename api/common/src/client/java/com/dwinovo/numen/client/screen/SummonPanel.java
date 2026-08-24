package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.llm.ProviderLibrary;
import com.dwinovo.numen.client.skin.SkinLibrary;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Dropdown;
import com.dwinovo.numen.client.ui.widget.InlineAlert;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.client.voice.VoiceLibrary;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.persona.PersonaLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 召唤卡——NumenUI 版的瓤:名字 + 人设/模型配置/模式/声线/皮肤五个选择。
 * 人设与声线可空(首项"不配置/无"),模型配置必选(库空则不出下拉,点创建时
 * 才解释——报错在动作处,不在氛围里);模式无 gamemode 权限时是置灰的继承档。
 * 校验错误内联在名字字段上,库为空的说明走页面级胶囊。
 */
public final class SummonPanel {

    /** 提交面:所有选择由面板收集,落库与发包留在宿主。 */
    public interface Host {
        void onCreate(Draft draft);

        void onCancel();

        /** 有 gamemode 权限(等级 2)才给模式下拉自选。 */
        boolean canChooseMode();

        /** 无权限时继承主人当前档。 */
        boolean ownerCreative();
    }

    /** 召唤草稿;{@code personaId/voiceId} 为 null = 不配置。 */
    public static final class Draft {
        public String name = "";
        public String personaId;
        public String providerId;
        public String voiceId;
        public String skinId;
        public boolean creative;
    }

    private static final String PERSONA_NONE = "__default__";
    private static final String VOICE_NONE = "__none__";
    private static final String SKIN_DEFAULT = "__default__";

    private final UiRoot ui = new UiRoot();
    private final Host host;
    private Draft draft = new Draft();

    private TextField nameField;
    private InlineAlert alert;
    private Button createButton;
    private List<String> personaIds = List.of();
    private List<String> providerIds = List.of();
    private List<String> voiceIds = List.of();
    private List<String> skinIds = List.of();
    private boolean hasProviders;
    private boolean hasVoices;
    private int modeBoxX, modeBoxY, modeBoxW;
    private boolean modeInherited;

    public SummonPanel(Host host) {
        this.host = host;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
    }

    /** 每次打开召唤流程:草稿归零(默认/无/生存)。 */
    public void reset() {
        draft = new Draft();
        draft.creative = host.canChooseMode() && draft.creative;
    }

    public void build(int x, int y, int w, int h, int dropBottom) {
        // 人设下拉的数据源是 persona/ 目录:每次打开召唤面板重扫一遍。
        PersonaLibrary.instance().reload();
        ui.clear();
        ui.setViewportHeight(dropBottom);

        int half = (w - 6) / 2;
        int ry = y;
        Label title = ui.add(new Label(t("numen.summon.title"), Label.Role.PRIMARY));
        title.setBounds(x, ry, w, 9);
        ry += 16;

        Label nameLabel = labelWidget(x, ry, ModLanguageData.Keys.SUMMON_NAME);
        ry += NumenStyle.LABEL_PITCH;
        nameField = ui.add(new TextField(draft.name, v -> draft.name = v)
                .placeholder(t(ModLanguageData.Keys.SUMMON_NAME_PLACEHOLDER))
                .withLabel(nameLabel));   // 出错时标签让位,免得两串文字叠在一行
        nameField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        // 人设可空:首项"不配置"(人设槽会告诉模型"未配置人设,可以自由发挥")。
        ry = label(x, ry, ModLanguageData.Keys.SUMMON_PERSONA_LABEL);
        List<String> personaNames = new ArrayList<>();
        List<String> pIds = new ArrayList<>();
        pIds.add(PERSONA_NONE);
        personaNames.add(t(ModLanguageData.Keys.SUMMON_PERSONA_NONE));
        for (PersonaLibrary.Persona p : PersonaLibrary.instance().list()) {
            pIds.add(p.id());
            personaNames.add(p.name());
        }
        personaIds = pIds;
        Dropdown personaPick = ui.add(new Dropdown(personaNames,
                Math.max(0, personaIds.indexOf(draft.personaId == null ? PERSONA_NONE : draft.personaId)),
                i -> {
                    String id = personaIds.get(i);
                    draft.personaId = PERSONA_NONE.equals(id) ? null : id;
                }));
        personaPick.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        // 模型配置必选(无默认项无兜底):库空则不出下拉,点创建时解释。
        int rowY = label(x, ry, ModLanguageData.Keys.PROVIDER_TITLE);
        label(x + half + 6, ry, "numen.summon.mode");
        var provEntries = ProviderLibrary.instance().list();
        hasProviders = !provEntries.isEmpty();
        if (hasProviders) {
            List<String> provNames = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (var e : provEntries) {
                ids.add(e.id());
                provNames.add(e.name());
            }
            providerIds = ids;
            if (draft.providerId == null) draft.providerId = ids.get(0);
            Dropdown provPick = ui.add(new Dropdown(provNames,
                    Math.max(0, providerIds.indexOf(draft.providerId)),
                    i -> draft.providerId = providerIds.get(i)));
            provPick.setBounds(x, rowY, half, NumenStyle.CONTROL_H);
        }
        modeInherited = !host.canChooseMode();
        if (modeInherited) {
            // 无 gamemode 权限:继承主人当前档,画成置灰格(render 里带悬停解释)。
            draft.creative = host.ownerCreative();
            modeBoxX = x + half + 6;
            modeBoxY = rowY;
            modeBoxW = half;
        } else {
            Dropdown modePick = ui.add(new Dropdown(List.of("生存", "创造"),
                    draft.creative ? 1 : 0, i -> draft.creative = i == 1));
            modePick.setBounds(x + half + 6, rowY, half, NumenStyle.CONTROL_H);
        }
        ry = rowY + NumenStyle.ROW_PITCH;

        // 声线可空(首项"无");皮肤默认按名字找同名正版。
        int rowY2 = label(x, ry, ModLanguageData.Keys.VOICE_SUMMON_LABEL);
        label(x + half + 6, ry, ModLanguageData.Keys.SUMMON_SKIN);
        var voiceEntries = VoiceLibrary.instance().list();
        hasVoices = !voiceEntries.isEmpty();
        if (hasVoices) {
            List<String> voiceNames = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            ids.add(VOICE_NONE);
            voiceNames.add(t(ModLanguageData.Keys.VOICE_BIND_NONE));
            for (var e : voiceEntries) {
                ids.add(e.id());
                voiceNames.add(e.name());
            }
            voiceIds = ids;
            Dropdown voicePick = ui.add(new Dropdown(voiceNames,
                    Math.max(0, voiceIds.indexOf(draft.voiceId == null ? VOICE_NONE : draft.voiceId)),
                    i -> {
                        String id = voiceIds.get(i);
                        draft.voiceId = VOICE_NONE.equals(id) ? null : id;
                    }));
            voicePick.setBounds(x, rowY2, half, NumenStyle.CONTROL_H);
        }
        List<String> skinNames = new ArrayList<>();
        List<String> sIds = new ArrayList<>();
        sIds.add(SKIN_DEFAULT);
        skinNames.add(t(ModLanguageData.Keys.SUMMON_SKIN_DEFAULT));
        for (var e : SkinLibrary.instance().list()) {
            if (e.signed()) {
                sIds.add(e.id());
                skinNames.add(e.name());
            }
        }
        skinIds = sIds;
        Dropdown skinPick = ui.add(new Dropdown(skinNames,
                Math.max(0, skinIds.indexOf(draft.skinId == null ? SKIN_DEFAULT : draft.skinId)),
                i -> draft.skinId = skinIds.get(i)));
        skinPick.setBounds(x + half + 6, rowY2, half, NumenStyle.CONTROL_H);
        ry = rowY2 + NumenStyle.ROW_PITCH + 4;

        alert = ui.add(new InlineAlert());
        alert.setBounds(x, y + 14, w, 24);

        int bw = 64, gap = 8;
        int bx = x + (w - (bw * 2 + gap)) / 2;
        Button cancel = ui.add(new Button(t("numen.gui.settings.cancel"),
                Button.Style.NORMAL, host::onCancel));
        cancel.setBounds(bx, ry, bw, 16);
        createButton = ui.add(new Button(t(ModLanguageData.Keys.SUMMON_CREATE),
                Button.Style.ACCENT, this::submit));
        createButton.setBounds(bx + bw + gap, ry, bw, 16);

        ui.requestFocus(nameField);
    }

    // ---- 宿主转发面 ----

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        if (modeInherited) {   // 置灰的继承档(不是控件:点不了才是本意)
            NumenStyle.fieldCard(s, modeBoxX, modeBoxY, modeBoxW, NumenStyle.CONTROL_H,
                    c.sectionBg(), c.inputBorder());
            s.drawText((draft.creative ? "创造" : "生存") + "(继承)",
                    modeBoxX + 5, modeBoxY + (NumenStyle.CONTROL_H - s.lineHeight()) / 2 + 1,
                    c.textMuted(), false);
        }
        ui.render(s, c, mouseX, mouseY, nowMs);
    }

    /** 悬停置灰模式格时的解释文案(宿主画 tooltip)。 */
    public String modeTooltipAt(double mx, double my) {
        if (!modeInherited) return null;
        boolean over = mx >= modeBoxX && mx < modeBoxX + modeBoxW
                && my >= modeBoxY && my < modeBoxY + NumenStyle.CONTROL_H;
        return over ? "没有 gamemode 权限——同伴将继承你当前的模式" : null;
    }

    public boolean mouseClicked(double mx, double my, int button) {
        return ui.mouseClicked(mx, my, button);
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        return ui.mouseScrolled(mx, my, delta);
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        if (keyCode == com.dwinovo.numen.client.ui.KeyCodes.ENTER && !ui.hasOverlay()) {
            submit();   // Enter 是确认的兜底路径
            return true;
        }
        return ui.keyPressed(keyCode, modifiers);
    }

    public boolean charTyped(char ch) {
        return ui.charTyped(ch);
    }

    // ---- 内部 ----

    private int label(int lx, int ly, String key) {
        labelWidget(lx, ly, key);
        return ly + NumenStyle.LABEL_PITCH;
    }

    private Label labelWidget(int lx, int ly, String key) {
        Label l = ui.add(new Label(t(key), Label.Role.MUTED));
        l.setBounds(lx, ly, 200, 9);
        return l;
    }

    /**
     * 创建前的三道校验:名字非空、模型配置在场、名字合规。
     * 名字限定 Minecraft 官方命名规则(3~16 位英文/数字/下划线)——中文名在玩家
     * 系统各处容易出错,而且名字同时就是皮肤来源:同名正版玩家的皮肤会自动穿上。
     */
    /** 提交后的等待态:胶囊说明在干嘛,创建钮自锁防重复点(异步查皮肤要一两秒)。 */
    public void setBusy(String message) {
        if (alert != null) alert.show(InlineAlert.Severity.INFO, message);
        if (createButton != null) createButton.setEnabled(false);
    }

    private void submit() {
        String n = draft.name == null ? "" : draft.name.trim();
        if (n.isEmpty()) {
            nameField.setError(t(ModLanguageData.Keys.SUMMON_WARN_NAME));
            return;
        }
        if (!hasProviders || draft.providerId == null) {
            // 库空是"去别处配"的事,不是这个字段填错了——页面级胶囊。
            alert.show(InlineAlert.Severity.ERROR, t(ModLanguageData.Keys.SUMMON_WARN_PROVIDER));
            return;
        }
        if (!com.dwinovo.numen.entity.MojangSkins.validName(n)) {   // 与服务端权威校验同一真源
            nameField.setError(t(ModLanguageData.Keys.SUMMON_WARN_NAME_FORMAT));
            return;
        }
        draft.name = n;
        host.onCreate(draft);
    }

    private static String t(String key) {
        return I18n.get(key);
    }
}
