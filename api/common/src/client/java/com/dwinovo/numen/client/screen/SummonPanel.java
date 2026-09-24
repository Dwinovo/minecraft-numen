package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.llm.ProviderLibrary;
import com.dwinovo.numen.client.skin.SkinLibrary;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.mc.Sprites;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.DialogBox;
import com.dwinovo.numen.client.ui.widget.Dropdown;
import com.dwinovo.numen.client.ui.widget.InlineAlert;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.voice.VoiceLibrary;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.persona.PersonaLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * 召唤卡——名字 + 人设/模型配置/模式/声线/皮肤五个选择。版式照 Telegram 的"新建联系人":
 * 标题下面左边一张空头像、右边名字输入框,再往下一行一个选择,右下取消/创建。
 * 人设与声线可空(首项"不配置/无"),模型配置必选(库空则那一行是空的,点创建时
 * 才解释——报错在动作处,不在氛围里);模式无 gamemode 权限时是置灰的继承档。
 * 校验错误内联在名字字段上,库为空的说明走页面级胶囊。
 */
public final class SummonPanel extends ModalCard {

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
    private boolean modeInherited;
    private Dropdown modePick;
    /** 头部的顶边(layout 时定)。 */
    private int coverTop;

    public SummonPanel(Host host) {
        this.host = host;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
        // 文本编辑交给真 EditBox(只收事件、不自绘),画面仍归 NumenUI。
        // 这是输入法辅助模组能认出这些框的前提——见 McTextInput。
        ui.setInputFactory(com.dwinovo.numen.client.ui.mc.McTextInput.factory());
    }

    /** 每次打开召唤流程:草稿归零(默认/无/生存)。 */
    @Override
    void reset() {
        draft = new Draft();
        draft.creative = host.canChooseMode() && draft.creative;
    }

    /** 头部(名字)+ 五行选择。 */
    @Override
    int height() {
        return heightFor(COVER_H + 5 * ROW_H);
    }

    @Override
    protected void layout(int top) {
        // 人设下拉的数据源是 persona/ 目录:每次打开召唤面板重扫一遍。
        PersonaLibrary.instance().reload();
        title(t("numen.summon.title"));
        coverTop = top;
        nameField = field(coverRight(), coverFieldY(top), coverRightW(), t(ModLanguageData.Keys.SUMMON_NAME),
                new TextField(draft.name, v -> draft.name = v)
                        .placeholder(t(ModLanguageData.Keys.SUMMON_NAME_PLACEHOLDER)));
        int ry = top + COVER_H;

        // 人设可空:首项"不配置"(没配的同伴用全局人设,全局也没配就用内置默认人设)。
        List<String> personaNames = new ArrayList<>();
        List<String> pIds = new ArrayList<>();
        pIds.add(PERSONA_NONE);
        personaNames.add(t(ModLanguageData.Keys.SUMMON_PERSONA_NONE));
        for (PersonaLibrary.Persona p : PersonaLibrary.instance().list()) {
            pIds.add(p.id());
            personaNames.add(p.name());
        }
        personaIds = pIds;
        select(ry, t(ModLanguageData.Keys.SUMMON_PERSONA_LABEL), personaNames,
                Math.max(0, personaIds.indexOf(draft.personaId == null ? PERSONA_NONE : draft.personaId)),
                i -> {
                    String id = personaIds.get(i);
                    draft.personaId = PERSONA_NONE.equals(id) ? null : id;
                });
        ry += ROW_H;

        // 模型配置必选(无默认项无兜底):库空则这一行是空的、点不开,点创建时解释。
        List<String> provNames = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (var e : ProviderLibrary.instance().list()) {
            ids.add(e.id());
            provNames.add(e.name());
        }
        hasProviders = !ids.isEmpty();
        providerIds = ids;
        if (hasProviders && draft.providerId == null) draft.providerId = ids.get(0);
        select(ry, t(ModLanguageData.Keys.PROVIDER_TITLE), provNames,
                Math.max(0, providerIds.indexOf(draft.providerId)),
                i -> draft.providerId = providerIds.get(i));
        ry += ROW_H;

        // 无 gamemode 权限:继承主人当前档,这一行置灰(悬停给解释)。
        modeInherited = !host.canChooseMode();
        String survival = t(ModLanguageData.Keys.SUMMON_MODE_SURVIVAL);
        String creative = t(ModLanguageData.Keys.SUMMON_MODE_CREATIVE);
        if (modeInherited) draft.creative = host.ownerCreative();
        modePick = select(ry, t("numen.summon.mode"),
                modeInherited
                        ? List.of(I18n.get(ModLanguageData.Keys.SUMMON_MODE_INHERITED, draft.creative ? creative : survival))
                        : List.of(survival, creative),
                modeInherited ? 0 : draft.creative ? 1 : 0,
                i -> draft.creative = i == 1);
        modePick.setEnabled(!modeInherited);
        ry += ROW_H;

        // 声线可空(首项"无");皮肤默认按名字找同名正版。
        List<String> voiceNames = new ArrayList<>();
        List<String> vIds = new ArrayList<>();
        vIds.add(VOICE_NONE);
        voiceNames.add(t(ModLanguageData.Keys.VOICE_BIND_NONE));
        for (var e : VoiceLibrary.instance().list()) {
            vIds.add(e.id());
            voiceNames.add(e.name());
        }
        voiceIds = vIds;
        select(ry, t(ModLanguageData.Keys.VOICE_SUMMON_LABEL), voiceNames,
                Math.max(0, voiceIds.indexOf(draft.voiceId == null ? VOICE_NONE : draft.voiceId)),
                i -> {
                    String id = voiceIds.get(i);
                    draft.voiceId = VOICE_NONE.equals(id) ? null : id;
                });
        ry += ROW_H;

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
        select(ry, t(ModLanguageData.Keys.SUMMON_SKIN), skinNames,
                Math.max(0, skinIds.indexOf(draft.skinId == null ? SKIN_DEFAULT : draft.skinId)),
                i -> draft.skinId = skinIds.get(i));

        // 页面级胶囊浮在标题那一行上:出现时它比标题要紧
        alert = ui.add(new InlineAlert());
        alert.setBounds(x + DialogBox.PAD_X, y + 2, w - DialogBox.PAD_X * 2, DialogBox.TITLE_H);

        createButton = buttons(t(ModLanguageData.Keys.GUI_SETTINGS_CANCEL), host::onCancel,
                t(ModLanguageData.Keys.SUMMON_CREATE), this::submit);

        ui.requestFocus(nameField);
    }

    // ---- 宿主转发面 ----

    /** 头部左边的空头像:她还没有脸,和 Telegram 新建联系人一样摆一个人形占位。 */
    @Override
    protected void paint(McDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, float alpha) {
        int px = photoX(), py = photoY(coverTop);
        NumenStyle.box(s, px, py, PHOTO, PHOTO, c.sectionBg(), c.inputBorder());
        int size = Sprites.SIZE * 2;   // 整数倍放大,像素图才不糊
        icon(s.graphics(), Sprites.USER, px + (PHOTO - size) / 2, py + (PHOTO - size) / 2, size,
                c.textSecondary(), alpha);
    }

    /** 悬停置灰模式行时的解释文案(宿主画 tooltip)。 */
    @Override
    String tooltipAt(double mx, double my) {
        return modeInherited && modePick != null && modePick.contains(mx, my)
                ? t(ModLanguageData.Keys.SUMMON_MODE_INHERIT_TIP) : null;
    }

    @Override
    boolean keyPressed(int keyCode, int modifiers) {
        if (keyCode == com.dwinovo.numen.client.ui.KeyCodes.ENTER && !ui.hasOverlay()) {
            submit();   // Enter 是确认的兜底路径
            return true;
        }
        return ui.keyPressed(keyCode, modifiers);
    }

    // ---- 内部 ----

    /** 提交后的等待态:胶囊说明在干嘛,创建钮自锁防重复点(异步查皮肤要一两秒)。 */
    public void setBusy(String message) {
        if (alert != null) alert.show(InlineAlert.Severity.INFO, message);
        if (createButton != null) createButton.setEnabled(false);
    }

    /**
     * 创建前的三道校验:名字非空、模型配置在场、名字合规。
     * 名字限定 Minecraft 官方命名规则(3~16 位英文/数字/下划线)——中文名在玩家
     * 系统各处容易出错,而且名字同时就是皮肤来源:同名正版玩家的皮肤会自动穿上。
     */
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
