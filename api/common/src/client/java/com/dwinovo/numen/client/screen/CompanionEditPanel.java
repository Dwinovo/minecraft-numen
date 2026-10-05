package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.llm.ProviderLibrary;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.CompanionHome;
import com.dwinovo.numen.client.agent.KnownSkins;
import com.dwinovo.numen.client.skin.CompanionFace;
import com.dwinovo.numen.client.skin.SkinLibrary;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.widget.Dropdown;
import com.dwinovo.numen.client.voice.VoiceLibrary;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.persona.PersonaLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 编辑卡——改一只<b>已创建</b>同伴:人设/模式/模型配置/声线/皮肤五个选择。版式照 Telegram 的"编辑联系人":
 * 标题下面是她的脸和名字,再往下一行一个选择,右下取消/保存。
 * 草稿制:选择行只改草稿,点保存才统一落地,且只发真正变过的项(换肤要原地重建身体,误触代价高);
 * 取消丢弃草稿。草稿基线在开卡时从各自的真源取一次({@link #reset()});皮肤的当前选择记在绑定里
 * (与档案/声线同模)。卡里只有"改","删"在头部菜单和资料页上,不在这张卡里。
 */
public final class CompanionEditPanel extends ModalCard {

    /** 屏幕侧的面:身份、网络动作与关卡。 */
    public interface Host {
        java.util.UUID uuid();

        String name();

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

    /** 编辑草稿;开卡时从真源取基线,保存时与基线比对只落差异。 */
    private static final class Draft {
        String personaId;     // null = 默认人设
        String providerId;    // null = 未绑定(只出现在老档,不许改回)
        String voiceId;       // null = 无声
        boolean creative;
        String skinId = SKIN_BY_NAME;
    }

    private final Host host;

    private Draft draft = new Draft();
    private String origPersona, origProvider, origVoice, origSkin;
    private boolean origCreative;

    private List<String> personaIds = List.of();
    private List<String> providerIds = List.of();
    private List<String> voiceIds = List.of();
    private List<String> skinIds = List.of();
    /** 没权限改模式时,模式那一行置灰(权限门在服务端也是同一道),悬停给解释。 */
    private boolean modeLocked;
    private Dropdown modePick;
    /** 头部的顶边(layout 时定)。 */
    private int coverTop;

    public CompanionEditPanel(Host host) {
        this.host = host;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
    }

    /** 每次开卡:草稿从当下真相取一次基线。 */
    @Override
    void reset() {
        var uuid = host.uuid();
        var loop = AgentLoopRegistry.getOrCreate(uuid);
        var binding = CompanionHome.binding(uuid);
        origPersona = loop.personaId();
        origProvider = binding.providerId();
        origVoice = binding.voiceId();
        origCreative = host.currentCreative();
        // 皮肤当前选择从绑定读;没记账或条目已删 = 按名字默认。
        String sk = binding.skinId();
        origSkin = sk != null && SkinLibrary.instance().get(sk) != null ? sk : SKIN_BY_NAME;
        draft = new Draft();
        draft.personaId = origPersona;
        draft.providerId = origProvider;
        draft.voiceId = origVoice;
        draft.creative = origCreative;
        draft.skinId = origSkin;
    }

    /** 头部 + 五行选择。 */
    @Override
    int height() {
        return heightFor(COVER_H + 5 * ROW_H);
    }

    @Override
    protected void layout(int top) {
        PersonaLibrary.instance().reload();   // 人设目录可能刚被增删,和召唤卡一样重扫
        title(t(ModLanguageData.Keys.EDIT_COMPANION_TITLE));
        coverTop = top;
        int ry = top + COVER_H;

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
        select(ry, t(ModLanguageData.Keys.SUMMON_PERSONA_LABEL), personaNames,
                Math.max(0, personaIds.indexOf(curPersona)),
                i -> {
                    String id = personaIds.get(i);
                    draft.personaId = PERSONA_NONE.equals(id) ? null : id;
                });
        ry += ROW_H;

        modeLocked = !host.canChooseMode();
        String survival = t(ModLanguageData.Keys.SUMMON_MODE_SURVIVAL);
        String creative = t(ModLanguageData.Keys.SUMMON_MODE_CREATIVE);
        // 改不了时只摆着当前档,置灰点不开(不是没有这一行:她此刻是什么模式照样要看得到)
        modePick = select(ry, t("numen.summon.mode"),
                modeLocked ? List.of(draft.creative ? creative : survival) : List.of(survival, creative),
                modeLocked ? 0 : draft.creative ? 1 : 0,
                i -> draft.creative = i == 1);
        modePick.setEnabled(!modeLocked);
        ry += ROW_H;

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
        select(ry, t(ModLanguageData.Keys.PROVIDER_TITLE), provNames,
                Math.max(0, providerIds.indexOf(draft.providerId == null ? "" : draft.providerId)),
                i -> {
                    String id = providerIds.get(i);
                    if (!id.isEmpty()) draft.providerId = id;
                });
        ry += ROW_H;

        List<String> voiceNames = new ArrayList<>();
        List<String> vIds = new ArrayList<>();
        vIds.add(VOICE_NONE);
        voiceNames.add(t(ModLanguageData.Keys.VOICE_BIND_NONE));
        for (var e : VoiceLibrary.instance().list()) {
            vIds.add(e.id());
            voiceNames.add(e.name());
        }
        voiceIds = vIds;
        String curVoice = draft.voiceId == null ? VOICE_NONE : draft.voiceId;
        select(ry, t(ModLanguageData.Keys.VOICE_SUMMON_LABEL), voiceNames,
                Math.max(0, voiceIds.indexOf(curVoice)),
                i -> {
                    String id = voiceIds.get(i);
                    draft.voiceId = VOICE_NONE.equals(id) ? null : id;
                });
        ry += ROW_H;

        // 皮肤:默认选中当前穿的(绑定里记的选择意图);保存时只有真换了才发包(换肤要原地重建身体)。
        List<String> skinNames = new ArrayList<>();
        List<String> sIds = new ArrayList<>();
        sIds.add(SKIN_BY_NAME);
        skinNames.add(t(ModLanguageData.Keys.SUMMON_SKIN_DEFAULT));
        for (var e : SkinLibrary.instance().list()) {
            if (e.signed()) {
                sIds.add(e.id());
                skinNames.add(e.name());
            }
        }
        skinIds = sIds;
        select(ry, t(ModLanguageData.Keys.SUMMON_SKIN), skinNames,
                Math.max(0, skinIds.indexOf(draft.skinId)),
                i -> draft.skinId = skinIds.get(i));

        buttons(t(ModLanguageData.Keys.GUI_SETTINGS_CANCEL), host::onClose,
                t(ModLanguageData.Keys.GUI_SETTINGS_SAVE), this::save);
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
        if (!draft.skinId.equals(origSkin)) {
            host.applySkin(draft.skinId);
            CompanionHome.bind(uuid, CompanionHome.binding(uuid)
                    .withSkin(SKIN_BY_NAME.equals(draft.skinId) ? null : draft.skinId));
        }
        host.onClose();
    }

    // ---- 宿主转发面 ----

    /** 头部:她的脸,旁边加粗的名字(脸是 MC 独有的东西,这一层可以画)。 */
    @Override
    protected void paint(McDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs, float alpha) {
        CompanionFace.draw(s.graphics(), host.uuid(), KnownSkins.of(host.uuid()), photoX(), photoY(coverTop), PHOTO);
        coverName(s, c, coverTop, host.name());
    }

    /** 悬停提示(宿主画 tooltip):置灰的模式行报锁因。 */
    @Override
    String tooltipAt(double mx, double my) {
        return modeLocked && modePick != null && modePick.contains(mx, my)
                ? t(ModLanguageData.Keys.EDIT_MODE_LOCKED) : null;
    }

    private static String t(String key) {
        return I18n.get(key);
    }
}
