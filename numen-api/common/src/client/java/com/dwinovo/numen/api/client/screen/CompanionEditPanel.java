package com.dwinovo.numen.api.client.screen;

import com.dwinovo.numen.api.client.agent.KnownSkins;
import com.dwinovo.numen.api.client.agent.RemoteCompanion;
import com.dwinovo.numen.api.client.agent.RemoteCompanions;
import com.dwinovo.numen.api.client.screen.SetupDraft.Item;
import com.dwinovo.numen.api.client.skin.CompanionFace;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.mc.GuiAlpha;
import com.dwinovo.numen.api.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.api.data.ModLanguageData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/**
 * 编辑卡——改一只<b>已创建</b>同伴:她的脸头部 + 配置行({@link SetupRows},与召唤卡同一套:人设、模型配置摆出,声线、模式收进
 * "更多选项")。版式照 Telegram 的"编辑联系人":标题下面是她的脸和名字,再往下是配置行,右下取消/保存。
 *
 * <p>草稿制:配置行只改草稿,点保存才统一落地,且只发真正变过的项;取消丢弃草稿。草稿基线在开卡时从她的绑定取一次
 * ({@link #reset()},服务端推来的状态)。头部那张脸是皮肤的入口:点开选皮肤卡,选定就是当场换(换肤要原地刷新身体,不进草稿)。
 * 卡里只有"改","删"在头部菜单和资料页上,不在这张卡里。
 */
public final class CompanionEditPanel extends ModalCard {

    /** 屏幕侧的面:身份、网络动作与关卡。 */
    public interface Host {
        java.util.UUID uuid();

        String name();

        void onClose();

        /** 与服务端模式门同一判据:有 gamemode 权限或主人本人在创造。 */
        boolean canChooseMode();

        /** 开选皮肤卡。 */
        void openSkins();

        /** 名册推来的此刻模式。 */
        boolean currentCreative();

        void setCreative(boolean creative);
    }

    private final Host host;
    private final SetupRows rows;

    public CompanionEditPanel(Host host) {
        this.host = host;
        this.rows = new SetupRows(SetupRows.Context.EDIT, host::canChooseMode);
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
    }

    /** 每次开卡:草稿从当下真相取一次基线,"更多选项"收起。 */
    @Override
    void reset() {
        RemoteCompanion her = RemoteCompanions.getOrCreate(host.uuid());
        rows.reset(SetupDraft.current(her.personaId(), her.providerEntryId(), her.voiceId(), host.currentCreative()));
    }

    /** 头部 + 配置行此刻的高。 */
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
        title(t(ModLanguageData.Keys.EDIT_COMPANION_TITLE));
        rows.layout(this, top + COVER_H);
        buttons(t(ModLanguageData.Keys.GUI_SETTINGS_CANCEL), host::onClose,
                t(ModLanguageData.Keys.GUI_SETTINGS_SAVE), this::save);
    }

    /** 保存:只落与开卡基线不同的项,然后关卡。 */
    private void save() {
        SetupDraft draft = rows.draft();
        RemoteCompanion her = RemoteCompanions.getOrCreate(host.uuid());
        for (Item item : draft.changes()) {
            if (item.slot() != null) {
                her.bind(item.slot(), (String) draft.value(item));
            } else if (item == Item.MODE) {
                host.setCreative((Boolean) draft.value(item));
            }
        }
        host.onClose();
    }

    // ---- 宿主转发面 ----

    /** 头部:她的脸,旁边加粗的名字(脸是 MC 独有的东西,这一层可以画);脸可以点,悬停描边。 */
    @Override
    protected void paint(McDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs, float alpha) {
        CompanionFace.draw(s.graphics(), host.uuid(), KnownSkins.of(host.uuid()), photoX(), photoY(coverTop()), PHOTO);
        GuiAlpha.set(s.graphics(), alpha);
        photoHover(s, c, mouseX, mouseY, alpha);
        coverName(s, c, coverTop(), host.name());
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

    private static String t(String key) {
        return I18n.get(key);
    }
}
