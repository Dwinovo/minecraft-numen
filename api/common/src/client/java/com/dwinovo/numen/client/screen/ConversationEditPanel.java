package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.KeyCodes;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.data.ModLanguageData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/**
 * 会话的编辑卡:改名。名字默认跟着成员走,留空就退回拼成员名——所以这里只有一个框,
 * 没有"恢复默认"钮。"删"(解散)在头部名字旁的垃圾桶上,不在这张卡里;成员在抬头那一行增减。
 */
public final class ConversationEditPanel implements ModalCard {

    /** 屏幕侧的面:哪个会话、存名字、关卡。 */
    public interface Host {
        Conversation conversation();

        void onSave(String name);

        void onClose();
    }

    private static final int CARD_H = 86;

    private final UiRoot ui = new UiRoot();
    private final Host host;
    private TextField nameField;
    private String draft = "";

    public ConversationEditPanel(Host host) {
        this.host = host;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
        // 文本编辑交给真 EditBox(只收事件、不自绘),画面仍归 NumenUI——见 McTextInput。
        ui.setInputFactory(com.dwinovo.numen.client.ui.mc.McTextInput.factory());
    }

    @Override
    public void reset() {
        String n = host.conversation().name();
        draft = n == null ? "" : n;
    }

    @Override
    public int height() {
        return CARD_H;
    }

    @Override
    public void build(int x, int y, int w, int h, int dropBottom) {
        ui.clear();
        ui.setViewportHeight(dropBottom);
        Conversation conv = host.conversation();

        int ry = y;
        Label title = ui.add(new Label(
                t(ModLanguageData.Keys.EDIT_TITLE) + " · " + conv.displayName(NumenRoster.instance()::name),
                Label.Role.PRIMARY));
        title.setBounds(x, ry + 5, w, 9);
        ry += 20;

        Label nameLabel = ui.add(new Label(t(ModLanguageData.Keys.CONVO_NAME_LABEL), Label.Role.MUTED));
        nameLabel.setBounds(x, ry, 200, 9);
        ry += NumenStyle.LABEL_PITCH;
        // 占位写的是留空之后会显示的那个名字,所见即所得
        nameField = ui.add(new TextField(draft, v -> draft = v)
                .placeholder(conv.withName(null).displayName(NumenRoster.instance()::name))
                .withLabel(nameLabel));
        nameField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH + 4;

        int bw = 64, gap = 8;
        int bx = x + (w - (bw * 2 + gap)) / 2;
        Button cancel = ui.add(new Button(t(ModLanguageData.Keys.GUI_SETTINGS_CANCEL),
                Button.Style.NORMAL, host::onClose));
        cancel.setBounds(bx, ry, bw, 16);
        Button save = ui.add(new Button(t(ModLanguageData.Keys.GUI_SETTINGS_SAVE),
                Button.Style.ACCENT, this::save));
        save.setBounds(bx + bw + gap, ry, bw, 16);
        ui.requestFocus(nameField);
    }

    private void save() {
        host.onSave(nameField == null ? draft : nameField.value());
        host.onClose();
    }

    // ---- 宿主转发面 ----

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        ui.render(s, c, mouseX, mouseY, nowMs);
    }

    @Override
    public String tooltipAt(double mx, double my) {
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        return ui.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        return ui.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int modifiers) {
        if (keyCode == KeyCodes.ENTER) {
            save();   // Enter 是确认的兜底路径
            return true;
        }
        return ui.keyPressed(keyCode, modifiers);
    }

    @Override
    public boolean charTyped(char ch) {
        return ui.charTyped(ch);
    }

    private static String t(String key) {
        return I18n.get(key);
    }
}
