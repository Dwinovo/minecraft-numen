package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.skin.ConversationFaces;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.data.ModLanguageData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/**
 * 会话的编辑卡:改名。版式照 Telegram 的建群卡:标题下面左边群头像、右边名字输入框,右下取消/保存。
 * 名字默认跟着成员走,留空就退回拼成员名——所以这里只有一个框,没有"恢复默认"钮。
 * "删"(解散)在头部菜单上,不在这张卡里;成员在抬头那一行增减。
 */
public final class ConversationEditPanel extends ModalCard {

    /** 屏幕侧的面:哪个会话、存名字、关卡。 */
    public interface Host {
        Conversation conversation();

        void onSave(String name);

        void onClose();
    }

    private final Host host;
    private TextField nameField;
    private String draft = "";
    /** 头部的顶边(layout 时定)。 */
    private int coverTop;

    public ConversationEditPanel(Host host) {
        this.host = host;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
        // 文本编辑交给真 EditBox(只收事件、不自绘),画面仍归 NumenUI——见 McTextInput。
        ui.setInputFactory(com.dwinovo.numen.client.ui.mc.McTextInput.factory());
    }

    @Override
    void reset() {
        String n = host.conversation().name();
        draft = n == null ? "" : n;
    }

    /** 只有头部(群头像 + 名字框)。 */
    @Override
    int height() {
        return heightFor(COVER_H);
    }

    @Override
    protected void layout(int top) {
        Conversation conv = host.conversation();
        title(t(ModLanguageData.Keys.CONVO_EDIT_TITLE));
        coverTop = top;
        // 占位写的是留空之后会显示的那个名字,所见即所得
        nameField = field(coverRight(), coverFieldY(top), coverRightW(), t(ModLanguageData.Keys.CONVO_NAME_LABEL),
                new TextField(draft, v -> draft = v)
                        .placeholder(conv.withName(null).displayName(NumenRoster.instance()::name)));
        buttons(t(ModLanguageData.Keys.GUI_SETTINGS_CANCEL), host::onClose,
                t(ModLanguageData.Keys.GUI_SETTINGS_SAVE), this::save);
        ui.requestFocus(nameField);
    }

    private void save() {
        host.onSave(nameField == null ? draft : nameField.value());
        host.onClose();
    }

    // ---- 宿主转发面 ----

    /** 头部左边的群头像(左栏里那一个)。 */
    @Override
    protected void paint(McDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs, float alpha) {
        ConversationFaces.draw(s.graphics(), host.conversation(), photoX(), photoY(coverTop), PHOTO);
    }

    private static String t(String key) {
        return I18n.get(key);
    }
}
