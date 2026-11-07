package com.dwinovo.numen.api.client.screen;

import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.api.client.ui.mc.Sprites;
import com.dwinovo.numen.api.client.ui.widget.Dropdown;
import com.dwinovo.numen.api.client.ui.widget.TextField;
import com.dwinovo.numen.api.data.ModLanguageData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.List;

/**
 * 召唤卡——名字 + 模式。版式照 Telegram 的"新建联系人":标题下面左边一张空头像、右边名字输入框,再往下一行一个选择,
 * 右下取消/创建。召唤只要名字,不设任何前提:模型档案、人设、声线、皮肤都是召唤之后在编辑卡里绑的。模式无 gamemode
 * 权限时是置灰的继承档。校验错误内联在名字字段上。
 */
public final class SummonPanel extends ModalCard {

    /** 提交面:选择由面板收集,发包留在宿主。 */
    public interface Host {
        void onCreate(Draft draft);

        void onCancel();

        /** 有 gamemode 权限(等级 2)才给模式下拉自选。 */
        boolean canChooseMode();

        /** 无权限时继承主人当前档。 */
        boolean ownerCreative();
    }

    /** 召唤草稿。 */
    public static final class Draft {
        public String name = "";
        public boolean creative;
    }

    private final Host host;
    private Draft draft = new Draft();

    private TextField nameField;
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
        ui.setInputFactory(com.dwinovo.numen.api.client.ui.mc.McTextInput.factory());
    }

    /** 每次打开召唤流程:草稿归零(生存)。 */
    @Override
    void reset() {
        draft = new Draft();
        draft.creative = host.canChooseMode() && draft.creative;
    }

    /** 头部(名字)+ 一行模式。 */
    @Override
    int height() {
        return heightFor(COVER_H + ROW_H);
    }

    @Override
    protected void layout(int top) {
        title(t("numen.summon.title"));
        coverTop = top;
        nameField = field(coverRight(), coverFieldY(top), coverRightW(), t(ModLanguageData.Keys.SUMMON_NAME),
                new TextField(draft.name, v -> draft.name = v)
                        .placeholder(t(ModLanguageData.Keys.SUMMON_NAME_PLACEHOLDER)));
        int ry = top + COVER_H;

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

        buttons(t(ModLanguageData.Keys.GUI_SETTINGS_CANCEL), host::onCancel,
                t(ModLanguageData.Keys.SUMMON_CREATE), this::submit);

        ui.requestFocus(nameField);
    }

    // ---- 宿主转发面 ----

    /** 头部左边的空头像:她还没有脸,和 Telegram 新建联系人一样摆一个人形占位。 */
    @Override
    protected void paint(McDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs, float alpha) {
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

    // ---- 内部 ----

    /**
     * 创建前的两道校验:名字非空、名字合规。名字限定 Minecraft 官方命名规则(3~16 位英文/数字/下划线)——
     * 中文名在玩家系统各处容易出错;与服务端权威校验同一真源。
     */
    private void submit() {
        String n = draft.name == null ? "" : draft.name.trim();
        if (n.isEmpty()) {
            nameField.setError(t(ModLanguageData.Keys.SUMMON_WARN_NAME));
            return;
        }
        if (!com.dwinovo.numen.api.entity.MojangSkins.validName(n)) {
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
