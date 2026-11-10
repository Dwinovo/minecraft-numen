package com.dwinovo.numen.api.client.screen;

import com.dwinovo.numen.api.client.skin.SkinChoices;
import com.dwinovo.numen.api.client.skin.SkinTextures;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.mc.GuiAlpha;
import com.dwinovo.numen.api.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.api.client.ui.mc.Sprites;
import com.dwinovo.numen.api.client.ui.widget.TextField;
import com.dwinovo.numen.api.data.ModLanguageData;
import com.dwinovo.numen.api.entity.SummonSetup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.resources.language.I18n;

/**
 * 召唤卡——名字头部 + 配置行({@link SetupRows})。版式照 Telegram 的新建群:标题下面左边一张头像、右边名字输入框,再往下是配置行,
 * 右下取消/召唤。名字是唯一必填的,校验错误内联在名字字段上;配置都不是必须的,每一项摆着不指定时的样子("默认(X)"),
 * 改过的随召唤请求一起发出,服务端在她到达的那一刻落下。头像是皮肤的入口:点它去选皮肤卡,选定的进草稿、头像换成那张脸。
 */
public final class SummonPanel extends ModalCard {

    /** 提交面:选择由面板收集,发包与开别的卡留在宿主。 */
    public interface Host {
        void onCreate(String name, SummonSetup setup);

        void onCancel();

        /** 与服务端模式门同一判据:有 gamemode 权限或主人本人在创造。 */
        boolean canChooseMode();

        /** 开选皮肤卡;选定或取消后回到这张卡,草稿还在。 */
        void openSkins();
    }

    private final Host host;
    private final SetupRows rows;
    private String name = "";

    private TextField nameField;

    public SummonPanel(Host host) {
        this.host = host;
        this.rows = new SetupRows(SetupRows.Context.SUMMON, host::canChooseMode);
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
        // 文本编辑交给真 EditBox(只收事件、不自绘),画面仍归 NumenUI。
        // 这是输入法辅助模组能认出这些框的前提——见 McTextInput。
        ui.setInputFactory(com.dwinovo.numen.api.client.ui.mc.McTextInput.factory());
    }

    /** 每次打开召唤流程:名字空着,配置回到默认,"更多选项"收起。 */
    @Override
    void reset() {
        name = "";
        rows.reset(SetupDraft.summonDefaults());
    }

    /** 草稿里的名字(选皮肤卡的标题用)。 */
    String draftName() {
        return name;
    }

    /** 草稿(选皮肤卡把选定的皮肤写进来)。 */
    SetupDraft draft() {
        return rows.draft();
    }

    /** 头部(名字)+ 配置行此刻的高。 */
    @Override
    int height() {
        return heightFor(COVER_H + rows.height());
    }

    @Override
    void advance(long nowMs) {
        rows.advance(nowMs);
    }

    @Override
    protected void layout(int top) {
        title(t("numen.summon.title"));
        nameField = field(coverRight(), coverFieldY(top), coverRightW(), t(ModLanguageData.Keys.SUMMON_NAME),
                new TextField(name, v -> name = v)
                        .placeholder(t(ModLanguageData.Keys.SUMMON_NAME_PLACEHOLDER)));
        rows.layout(this, top + COVER_H);
        buttons(t(ModLanguageData.Keys.GUI_SETTINGS_CANCEL), host::onCancel,
                t(ModLanguageData.Keys.SUMMON_CREATE), this::submit);
        ui.requestFocus(nameField);
    }

    // ---- 宿主转发面 ----

    /** 头部左边的头像:选了皮肤是那张脸;没选是人形占位(默认皮肤按她的 UUID 挑,召唤之前还不知道是哪一张)。 */
    @Override
    protected void paint(McDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs, float alpha) {
        int px = photoX(), py = photoY(coverTop());
        SkinChoices.Pick pick = rows.draft().skin();
        var face = pick == null ? null
                : SkinTextures.faceOf(pick.choice().where() + ":" + pick.choice().name(), pick.choice().png());
        if (face != null) {
            PlayerFaceRenderer.draw(s.graphics(), face, px, py, PHOTO, true, false, -1);
            GuiAlpha.set(s.graphics(), alpha);
        } else {
            NumenStyle.box(s, px, py, PHOTO, PHOTO, c.sectionBg(), c.inputBorder());
            int size = Sprites.SIZE * 2;   // 整数倍放大,像素图才不糊
            icon(s.graphics(), Sprites.USER, px + (PHOTO - size) / 2, py + (PHOTO - size) / 2, size,
                    c.textSecondary(), alpha);
        }
        photoHover(s, c, mouseX, mouseY, alpha);
    }

    @Override
    boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && !ui.hasOverlay() && photoAt(mx, my)) {
            host.openSkins();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    String tooltipAt(double mx, double my) {
        if (photoAt(mx, my)) {
            return t(ModLanguageData.Keys.SETUP_SKIN_CHOOSE);
        }
        return rows.tooltipAt(mx, my);
    }

    // ---- 内部 ----

    /**
     * 召唤前的两道校验:名字非空、名字合规。名字限定 Minecraft 官方命名规则(3~16 位英文/数字/下划线)——
     * 中文名在玩家系统各处容易出错;与服务端权威校验同一真源。配置不在这里校验:能不能绑由服务端照编辑时的规矩判,不行如实拒绝。
     */
    private void submit() {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            nameField.setError(t(ModLanguageData.Keys.SUMMON_WARN_NAME));
            return;
        }
        if (!com.dwinovo.numen.api.entity.MojangSkins.validName(n)) {
            nameField.setError(t(ModLanguageData.Keys.SUMMON_WARN_NAME_FORMAT));
            return;
        }
        name = n;
        host.onCreate(n, rows.draft().summonSetup());
    }

    private static String t(String key) {
        return I18n.get(key);
    }
}
