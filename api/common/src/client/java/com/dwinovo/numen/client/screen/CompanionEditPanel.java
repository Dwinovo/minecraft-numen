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
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.client.voice.VoiceLibrary;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.persona.PersonaLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 编辑卡——点当前激活头像打开,改一只<b>已创建</b>同伴:人设/模式/模型配置/声线/
 * 皮肤五个选择 + 遣散。草稿制:下拉只改草稿,点保存才统一落地,且只发真正变过的
 * 项(换肤要原地重建身体,误触代价高);取消丢弃草稿。草稿基线在开卡时从各自的
 * 真源取一次({@link #reset()})。皮肤是唯一读不到当前值的(真源在服务端注册表),
 * 首项"保持现状"如实表达这一点,保存时只有选了别项才发包。
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

    /** 编辑草稿;开卡时从真源取基线,保存时与基线比对只落差异。 */
    private static final class Draft {
        String personaId;     // null = 默认人设
        String providerId;    // null = 未绑定(只出现在老档,不许改回)
        String voiceId;       // null = 无声
        boolean creative;
        String skinId = SKIN_KEEP;
    }

    private final UiRoot ui = new UiRoot();
    private final Host host;

    private Draft draft = new Draft();
    private String origPersona, origProvider, origVoice;
    private boolean origCreative;

    private List<String> personaIds = List.of();
    private List<String> providerIds = List.of();
    private List<String> voiceIds = List.of();
    private List<String> skinIds = List.of();
    private int modeBoxX, modeBoxY, modeBoxW;
    private boolean modeLocked;

    public CompanionEditPanel(Host host) {
        this.host = host;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
    }

    /** 每次开卡:草稿从当下真相取一次基线。 */
    public void reset() {
        var uuid = host.uuid();
        var loop = AgentLoopRegistry.getOrCreate(uuid);
        var binding = CompanionHome.binding(uuid);
        origPersona = loop.personaId();
        origProvider = binding.providerId();
        origVoice = binding.voiceId();
        origCreative = host.currentCreative();
        draft = new Draft();
        draft.personaId = origPersona;
        draft.providerId = origProvider;
        draft.voiceId = origVoice;
        draft.creative = origCreative;
    }

    public void build(int x, int y, int w, int h, int dropBottom) {
        PersonaLibrary.instance().reload();   // 人设目录可能刚被增删,和召唤卡一样重扫
        ui.clear();
        ui.setViewportHeight(dropBottom);

        int half = (w - 6) / 2;
        int ry = y;
        // 头像由屏幕画在标题左侧(面板不碰 GuiGraphics),文字给它让出 24px。
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
        String curPersona = draft.personaId == null ? PERSONA_NONE : draft.personaId;
        Dropdown personaPick = ui.add(new Dropdown(personaNames,
                Math.max(0, personaIds.indexOf(curPersona)),
                i -> {
                    String id = personaIds.get(i);
                    draft.personaId = PERSONA_NONE.equals(id) ? null : id;
                }));
        personaPick.setBounds(x, rowY, half, NumenStyle.CONTROL_H);

        modeLocked = !host.canChooseMode();
        if (modeLocked) {
            // 改不了(权限门在服务端也是同一道):画成置灰格,悬停给解释。
            modeBoxX = x + half + 6;
            modeBoxY = rowY;
            modeBoxW = half;
        } else {
            Dropdown modePick = ui.add(new Dropdown(
                    List.of(t(ModLanguageData.Keys.SUMMON_MODE_SURVIVAL),
                            t(ModLanguageData.Keys.SUMMON_MODE_CREATIVE)),
                    draft.creative ? 1 : 0, i -> draft.creative = i == 1));
            modePick.setBounds(x + half + 6, rowY, half, NumenStyle.CONTROL_H);
        }
        ry = rowY + NumenStyle.ROW_PITCH;

        // 模型配置 | 声线
        int rowY2 = label(x, ry, ModLanguageData.Keys.PROVIDER_TITLE);
        label(x + half + 6, ry, ModLanguageData.Keys.VOICE_SUMMON_LABEL);
        List<String> provNames = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        if (draft.providerId == null) {
            // 老档没绑过:占位首项如实显示,选中任一真档案即入草稿(不许改回"未绑定")。
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
                    Math.max(0, providerIds.indexOf(draft.providerId == null ? "" : draft.providerId)),
                    i -> {
                        String id = providerIds.get(i);
                        if (!id.isEmpty()) draft.providerId = id;
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
            String curVoice = draft.voiceId == null ? VOICE_NONE : draft.voiceId;
            Dropdown voicePick = ui.add(new Dropdown(voiceNames,
                    Math.max(0, voiceIds.indexOf(curVoice)),
                    i -> {
                        String id = voiceIds.get(i);
                        draft.voiceId = VOICE_NONE.equals(id) ? null : id;
                    }));
            voicePick.setBounds(x + half + 6, rowY2, half, NumenStyle.CONTROL_H);
        }
        ry = rowY2 + NumenStyle.ROW_PITCH;

        // 皮肤:整行宽。首项"保持现状"是真话——当前穿的哪张住在服务端注册表里,
        // 客户端不留副本;保存时只有选了别项才发包(换肤要原地重建身体)。
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
        Dropdown skinPick = ui.add(new Dropdown(skinNames,
                Math.max(0, skinIds.indexOf(draft.skinId)),
                i -> draft.skinId = skinIds.get(i)));
        skinPick.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH + 4;

        int bw = 64, gap = 8;
        int bx = x + (w - (bw * 3 + gap * 2)) / 2;
        Button dismiss = ui.add(new Button(t(ModLanguageData.Keys.EDIT_DISMISS),
                Button.Style.DANGER, host::onDismiss));
        dismiss.setBounds(bx, ry, bw, 16);
        Button cancel = ui.add(new Button(t("numen.gui.settings.cancel"),
                Button.Style.NORMAL, host::onClose));
        cancel.setBounds(bx + bw + gap, ry, bw, 16);
        Button save = ui.add(new Button(t(ModLanguageData.Keys.GUI_SETTINGS_SAVE),
                Button.Style.ACCENT, this::save));
        save.setBounds(bx + (bw + gap) * 2, ry, bw, 16);
    }

    /** 保存:与开卡基线比对,只落真正变过的项,然后关卡。 */
    private void save() {
        var uuid = host.uuid();
        if (!Objects.equals(draft.personaId, origPersona)) {
            AgentLoopRegistry.getOrCreate(uuid).setPersona(draft.personaId);
        }
        if (draft.providerId != null && !draft.providerId.equals(origProvider)) {
            AgentLoopRegistry.getOrCreate(uuid).setProviderEntry(draft.providerId);
        }
        if (!Objects.equals(draft.voiceId, origVoice)) {
            CompanionHome.bind(uuid, CompanionHome.binding(uuid).withVoice(draft.voiceId));
        }
        if (!modeLocked && draft.creative != origCreative) {
            host.setCreative(draft.creative);
        }
        if (!SKIN_KEEP.equals(draft.skinId)) {
            host.applySkin(draft.skinId);
        }
        host.onClose();
    }

    // ---- 宿主转发面 ----

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        if (modeLocked) {   // 置灰的当前档(不是控件:点不了才是本意)
            NumenStyle.fieldCard(s, modeBoxX, modeBoxY, modeBoxW, NumenStyle.CONTROL_H,
                    c.sectionBg(), c.inputBorder());
            s.drawText(t(draft.creative ? ModLanguageData.Keys.SUMMON_MODE_CREATIVE
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
