package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.llm.ProviderLibrary;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.CompanionHome;
import com.dwinovo.numen.client.skin.SkinLibrary;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Dropdown;
import com.dwinovo.numen.client.ui.widget.InlineAlert;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.client.voice.VoiceLibrary;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.persona.PersonaLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * 编辑卡——点当前激活头像打开,改一只<b>已创建</b>同伴:人设/模式/模型配置/声线/
 * 皮肤五个选择 + 遣散。没有"保存":每个选择当场落地(人设/模型/声线是客户端绑定,
 * 模式/皮肤发包给服务端),卡上显示的当前值在 build 时从各自的真源读出。
 * 皮肤是唯一读不到当前值的(真源在服务端注册表),首项"保持现状"如实表达这一点。
 */
public final class CompanionEditPanel {

    /** 屏幕侧的面:身份、网络动作与关卡。 */
    public interface Host {
        java.util.UUID uuid();

        String name();

        /** 关卡并弹遣散确认(危险操作的闸在屏幕层)。 */
        void onDismiss();

        void onClose();

        /** 与服务端 applyGameMode 的门同一判据:有 gamemode 权限或主人在创造。 */
        boolean canChooseMode();

        /** 名册推来的此刻模式。 */
        boolean currentCreative();

        void setCreative(boolean creative);

        /** {@code skinId} 是皮肤库条目 id,或 {@link #SKIN_BY_NAME}(按名字查同名正版)。 */
        void applySkin(String skinId);
    }

    private static final String PERSONA_NONE = "__default__";
    private static final String VOICE_NONE = "__none__";
    /** 与召唤卡的"默认(按名字)"同义:本机查同名正版,查不到落回原版默认皮肤。 */
    public static final String SKIN_BY_NAME = "__default__";
    private static final String SKIN_KEEP = "__keep__";

    private final UiRoot ui = new UiRoot();
    private final Host host;

    private InlineAlert alert;
    private List<String> personaIds = List.of();
    private List<String> providerIds = List.of();
    private List<String> voiceIds = List.of();
    private List<String> skinIds = List.of();
    private int modeBoxX, modeBoxY, modeBoxW;
    private boolean modeLocked;
    private boolean modeCreative;

    public CompanionEditPanel(Host host) {
        this.host = host;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
    }

    public void build(int x, int y, int w, int h, int dropBottom) {
        PersonaLibrary.instance().reload();   // 人设目录可能刚被增删,和召唤卡一样重扫
        ui.clear();
        ui.setViewportHeight(dropBottom);
        var uuid = host.uuid();
        var loop = AgentLoopRegistry.getOrCreate(uuid);
        var binding = CompanionHome.binding(uuid);

        int half = (w - 6) / 2;
        int ry = y;
        // 头像由屏幕画在标题左侧(面板不碰 GuiGraphicsExtractor),文字给它让出 24px。
        Label title = ui.add(new Label(
                t(ModLanguageData.Keys.EDIT_TITLE) + " · " + host.name(), Label.Role.PRIMARY));
        title.setBounds(x + 24, ry + 5, w - 24, 9);
        ry += 24;

        // 人设 | 模式
        int rowY = label(x, ry, ModLanguageData.Keys.SUMMON_PERSONA_LABEL);
        label(x + half + 6, ry, "numen.summon.mode");
        List<String> personaNames = new ArrayList<>();
        List<String> pIds = new ArrayList<>();
        pIds.add(PERSONA_NONE);
        personaNames.add(t(ModLanguageData.Keys.SUMMON_PERSONA_NONE));
        for (PersonaLibrary.Persona p : PersonaLibrary.instance().list()) {
            pIds.add(p.id());
            personaNames.add(p.name());
        }
        personaIds = pIds;
        String curPersona = loop.personaId() == null ? PERSONA_NONE : loop.personaId();
        Dropdown personaPick = ui.add(new Dropdown(personaNames,
                Math.max(0, personaIds.indexOf(curPersona)),
                i -> {
                    String id = personaIds.get(i);
                    AgentLoopRegistry.getOrCreate(host.uuid())
                            .setPersona(PERSONA_NONE.equals(id) ? null : id);
                }));
        personaPick.setBounds(x, rowY, half, NumenStyle.CONTROL_H);

        modeLocked = !host.canChooseMode();
        modeCreative = host.currentCreative();
        if (modeLocked) {
            // 改不了(权限门在服务端也是同一道):画成置灰格,悬停给解释。
            modeBoxX = x + half + 6;
            modeBoxY = rowY;
            modeBoxW = half;
        } else {
            Dropdown modePick = ui.add(new Dropdown(
                    List.of(t(ModLanguageData.Keys.SUMMON_MODE_SURVIVAL),
                            t(ModLanguageData.Keys.SUMMON_MODE_CREATIVE)),
                    modeCreative ? 1 : 0, i -> host.setCreative(i == 1)));
            modePick.setBounds(x + half + 6, rowY, half, NumenStyle.CONTROL_H);
        }
        ry = rowY + NumenStyle.ROW_PITCH;

        // 模型配置 | 声线
        int rowY2 = label(x, ry, ModLanguageData.Keys.PROVIDER_TITLE);
        label(x + half + 6, ry, ModLanguageData.Keys.VOICE_SUMMON_LABEL);
        List<String> provNames = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        String curProv = binding.providerId();
        if (curProv == null) {
            // 老档没绑过:占位首项如实显示,选中任一真档案即绑定(不许绑回"未绑定")。
            ids.add("");
            provNames.add(t(ModLanguageData.Keys.EDIT_PROVIDER_UNBOUND));
        }
        for (var e : ProviderLibrary.instance().list()) {
            ids.add(e.id());
            provNames.add(e.name());
        }
        providerIds = ids;
        if (!ids.isEmpty()) {
            Dropdown provPick = ui.add(new Dropdown(provNames,
                    Math.max(0, providerIds.indexOf(curProv == null ? "" : curProv)),
                    i -> {
                        String id = providerIds.get(i);
                        if (!id.isEmpty()) {
                            AgentLoopRegistry.getOrCreate(host.uuid()).setProviderEntry(id);
                        }
                    }));
            provPick.setBounds(x, rowY2, half, NumenStyle.CONTROL_H);
        }
        var voiceEntries = VoiceLibrary.instance().list();
        if (!voiceEntries.isEmpty()) {
            List<String> voiceNames = new ArrayList<>();
            List<String> vIds = new ArrayList<>();
            vIds.add(VOICE_NONE);
            voiceNames.add(t(ModLanguageData.Keys.VOICE_BIND_NONE));
            for (var e : voiceEntries) {
                vIds.add(e.id());
                voiceNames.add(e.name());
            }
            voiceIds = vIds;
            String curVoice = binding.voiceId() == null ? VOICE_NONE : binding.voiceId();
            Dropdown voicePick = ui.add(new Dropdown(voiceNames,
                    Math.max(0, voiceIds.indexOf(curVoice)),
                    i -> {
                        String id = voiceIds.get(i);
                        CompanionHome.bind(host.uuid(), CompanionHome.binding(host.uuid())
                                .withVoice(VOICE_NONE.equals(id) ? null : id));
                    }));
            voicePick.setBounds(x + half + 6, rowY2, half, NumenStyle.CONTROL_H);
        }
        ry = rowY2 + NumenStyle.ROW_PITCH;

        // 皮肤:整行宽。首项"保持现状"是真话——当前穿的哪张住在服务端注册表里,
        // 客户端不留副本;其余项选中即换(签名库条目直发,按名字走异步查询)。
        ry = label(x, ry, ModLanguageData.Keys.SUMMON_SKIN);
        List<String> skinNames = new ArrayList<>();
        List<String> sIds = new ArrayList<>();
        sIds.add(SKIN_KEEP);
        skinNames.add(t(ModLanguageData.Keys.EDIT_SKIN_KEEP));
        sIds.add(SKIN_BY_NAME);
        skinNames.add(t(ModLanguageData.Keys.SUMMON_SKIN_DEFAULT));
        for (var e : SkinLibrary.instance().list()) {
            if (e.signed()) {
                sIds.add(e.id());
                skinNames.add(e.name());
            }
        }
        skinIds = sIds;
        Dropdown skinPick = ui.add(new Dropdown(skinNames, 0,
                i -> {
                    String id = skinIds.get(i);
                    if (!SKIN_KEEP.equals(id)) host.applySkin(id);
                }));
        skinPick.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH + 4;

        alert = ui.add(new InlineAlert());
        alert.setBounds(x, y + 14, w, 24);

        int bw = 64, gap = 8;
        int bx = x + (w - (bw * 2 + gap)) / 2;
        Button dismiss = ui.add(new Button(t(ModLanguageData.Keys.EDIT_DISMISS),
                Button.Style.DANGER, host::onDismiss));
        dismiss.setBounds(bx, ry, bw, 16);
        Button close = ui.add(new Button(t(ModLanguageData.Keys.EDIT_CLOSE),
                Button.Style.NORMAL, host::onClose));
        close.setBounds(bx + bw + gap, ry, bw, 16);
    }

    // ---- 宿主转发面 ----

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        if (modeLocked) {   // 置灰的当前档(不是控件:点不了才是本意)
            NumenStyle.fieldCard(s, modeBoxX, modeBoxY, modeBoxW, NumenStyle.CONTROL_H,
                    c.sectionBg(), c.inputBorder());
            s.drawText(t(modeCreative ? ModLanguageData.Keys.SUMMON_MODE_CREATIVE
                            : ModLanguageData.Keys.SUMMON_MODE_SURVIVAL),
                    modeBoxX + 5, modeBoxY + (NumenStyle.CONTROL_H - s.lineHeight()) / 2 + 1,
                    c.textMuted(), false);
        }
        ui.render(s, c, mouseX, mouseY, nowMs);
    }

    /** 悬停置灰模式格时的解释文案(宿主画 tooltip)。 */
    public String modeTooltipAt(double mx, double my) {
        if (!modeLocked) return null;
        boolean over = mx >= modeBoxX && mx < modeBoxX + modeBoxW
                && my >= modeBoxY && my < modeBoxY + NumenStyle.CONTROL_H;
        return over ? t(ModLanguageData.Keys.EDIT_MODE_LOCKED) : null;
    }

    /** 异步动作的等待说明(如按名字查皮肤),胶囊显示。 */
    public void note(String message) {
        if (alert != null) alert.show(InlineAlert.Severity.INFO, message);
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

    private int label(int lx, int ly, String key) {
        Label l = ui.add(new Label(t(key), Label.Role.MUTED));
        l.setBounds(lx, ly, 200, 9);
        return ly + NumenStyle.LABEL_PITCH;
    }

    private static String t(String key) {
        return I18n.get(key);
    }
}
