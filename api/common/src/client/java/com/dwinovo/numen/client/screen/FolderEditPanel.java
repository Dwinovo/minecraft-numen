package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.client.agent.ChatFolders;
import com.dwinovo.numen.client.agent.Conversations;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.skin.ConversationFaces;
import com.dwinovo.numen.client.ui.Anim;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.KeyCodes;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.TextClip;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.data.ModLanguageData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 分组卡(Telegram 的新建/编辑分组):一个名字框,下面一格格会话的头像,点一下收进来、再点拿出去;
 * 收尾 [取消][保存]。名字空着或一个会话都没勾时保存点不动——Telegram 也要名字、也要至少一个会话。
 * 和邀请卡同一族:头像格的画法、勾上的那枚角标都照它。
 *
 * <p>会话多了一屏放不下两行时,头像格上下滚。
 */
public final class FolderEditPanel implements ModalCard {

    /** 屏幕侧的面:改的是哪个分组、存、关卡。 */
    public interface Host {
        /** 开卡时的底稿:编辑是那个分组本身;新建是 id 为 null 的一份(可能已经预先勾上了一个会话)。 */
        ChatFolders.Folder draft();

        void onSave(String name, List<String> conversationIds);

        void onClose();
    }

    private static final int FACE = 26;
    private static final int TILE_W = 40;
    private static final int TILE_GAP = 4;
    private static final int NAME_H = 9;
    /** 一格的高:脸、名字,再留一道缝。 */
    private static final int TILE_H = FACE + 4 + NAME_H + 5;
    /** 头像格露出的行数;更多就滚。 */
    private static final int ROWS = 2;
    private static final int FOOTER_H = 16;
    private static final int BTN_W = 52;

    private final UiRoot ui = new UiRoot();
    private final Host host;
    private ChatFolders.Folder draft;
    private String name = "";
    private List<Conversation> everyone = List.of();
    private final Set<String> picked = new LinkedHashSet<>();
    private Button save;
    /** 头像格的几何(渲染与命中共用)。 */
    private int gridX, gridY, gridW, perRow;
    /** 头像格滚了多少像素,按趋近走向 scrollTo。 */
    private float scroll, scrollTo;
    private long lastFrameMs;

    public FolderEditPanel(Host host) {
        this.host = host;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
        // 文本编辑交给真 EditBox(只收事件、不自绘),画面仍归 NumenUI——见 McTextInput。
        ui.setInputFactory(com.dwinovo.numen.client.ui.mc.McTextInput.factory());
    }

    @Override
    public void reset() {
        draft = host.draft();
        name = draft.name();
        everyone = Conversations.instance().all();
        picked.clear();
        // 只认此刻还在左栏上的:遣散掉的那只的私聊在这里没有格子,也就不该算"勾着"
        for (Conversation conv : everyone) {
            if (draft.chats().contains(conv.id())) picked.add(conv.id());
        }
        scroll = scrollTo = 0f;
    }

    @Override
    public int width() {
        return 320;
    }

    @Override
    public int height() {
        return 20 + NumenStyle.LABEL_PITCH + NumenStyle.ROW_PITCH + 2 + NumenStyle.LABEL_PITCH
                + ROWS * TILE_H + 6 + FOOTER_H + 8;
    }

    @Override
    public void build(int x, int y, int w, int h, int dropBottom) {
        ui.clear();
        ui.setViewportHeight(dropBottom);

        int ry = y;
        Label title = ui.add(new Label(t(draft.id() == null ? ModLanguageData.Keys.FOLDER_NEW
                : ModLanguageData.Keys.FOLDER_EDIT), Label.Role.PRIMARY));
        title.setBounds(x, ry + 5, w, 9);
        ry += 20;

        Label nameLabel = ui.add(new Label(t(ModLanguageData.Keys.FOLDER_NAME), Label.Role.MUTED));
        nameLabel.setBounds(x, ry, 200, 9);
        ry += NumenStyle.LABEL_PITCH;
        TextField nameField = ui.add(new TextField(name, v -> name = v).withLabel(nameLabel));
        nameField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH + 2;

        Label chatsLabel = ui.add(new Label(t(ModLanguageData.Keys.FOLDER_CHATS), Label.Role.MUTED));
        chatsLabel.setBounds(x, ry, 200, 9);
        ry += NumenStyle.LABEL_PITCH;
        perRow = Math.max(1, (w + TILE_GAP) / (TILE_W + TILE_GAP));
        gridW = perRow * TILE_W + (perRow - 1) * TILE_GAP;
        gridX = x;
        gridY = ry;
        ry += ROWS * TILE_H + 6;

        int gap = 6;
        int bx = x + w - (BTN_W * 2 + gap);   // Telegram 对话框的按钮靠右下
        Button cancel = ui.add(new Button(t(ModLanguageData.Keys.GUI_SETTINGS_CANCEL),
                Button.Style.LINK, host::onClose));
        cancel.setBounds(bx, ry, BTN_W, FOOTER_H);
        save = ui.add(new Button(t(ModLanguageData.Keys.GUI_SETTINGS_SAVE), Button.Style.LINK, this::confirm));
        save.setBounds(bx + BTN_W + gap, ry, BTN_W, FOOTER_H);
        save.setEnabled(canSave());
        ui.requestFocus(nameField);
    }

    private boolean canSave() {
        return !name.isBlank() && !picked.isEmpty();
    }

    private void confirm() {
        if (!canSave()) return;
        host.onSave(name, new ArrayList<>(picked));
        host.onClose();
    }

    private int rowsTotal() {
        return (everyone.size() + perRow - 1) / perRow;
    }

    private float maxScroll() {
        return Math.max(0, rowsTotal() * TILE_H - ROWS * TILE_H);
    }

    private int tileX(int i) {
        return gridX + (i % perRow) * (TILE_W + TILE_GAP);
    }

    private int tileY(int i) {
        return gridY + (i / perRow) * TILE_H - Math.round(scroll);
    }

    // ---- 宿主转发面 ----

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        float dt = lastFrameMs == 0 ? 0.016f : Math.min(0.1f, (nowMs - lastFrameMs) / 1000f);
        lastFrameMs = nowMs;
        scroll = Anim.approach(scroll, scrollTo, 18f, dt);
        int hot = tileAt(mouseX, mouseY);
        int gridH = ROWS * TILE_H;
        s.pushScissor(gridX - 3, gridY - 3, gridW + 6, gridH + 3);
        for (int i = 0; i < everyone.size(); i++) {
            Conversation conv = everyone.get(i);
            int tx = tileX(i), ty = tileY(i);
            if (ty + TILE_H < gridY || ty > gridY + gridH) continue;
            boolean on = picked.contains(conv.id());
            int fx = tx + (TILE_W - FACE) / 2, fy = ty + 2;
            int border = on ? c.accent() : hot == i ? NumenStyle.hoverBrighten(c.inputBorder()) : c.inputBorder();
            NumenStyle.box(s, fx - 2, fy - 2, FACE + 4, FACE + 4, c.inputBg(), border);
            if (s instanceof McDrawSurface m) {
                ConversationFaces.draw(m.graphics(), conv, fx, fy, FACE);
            }
            if (on) {
                // 勾上的右上角一小块强调色,和邀请卡同一个记号
                s.fillRect(fx + FACE - 5, fy - 3, 7, 7, c.accent());
            }
            String shown = TextClip.fit(s, conv.displayName(NumenRoster.instance()::name), TILE_W);
            s.drawText(shown, tx + (TILE_W - s.textWidth(shown)) / 2, fy + FACE + 4,
                    on ? c.textPrimary() : c.textMuted(), false);
        }
        s.popScissor();
        float max = maxScroll();
        if (max > 0) {
            // 滚得动时右缘一道细滑块:露出的那段占全长的比例
            int barH = Math.max(6, Math.round(gridH * gridH / (gridH + max)));
            int barY = gridY + Math.round((gridH - barH) * (scroll / max));
            s.fillRect(gridX + gridW + 4, barY, NumenStyle.SCROLLBAR_W, barH, c.inputBorder());
        }
        if (save != null) save.setEnabled(canSave());
        ui.render(s, c, mouseX, mouseY, nowMs);
    }

    /** 鼠标下的那一格(everyone 下标),不在格上或滚出去了则 -1。 */
    private int tileAt(double mx, double my) {
        if (my < gridY || my >= gridY + ROWS * TILE_H || mx < gridX || mx >= gridX + gridW) return -1;
        for (int i = 0; i < everyone.size(); i++) {
            int tx = tileX(i), ty = tileY(i);
            if (mx >= tx && mx < tx + TILE_W && my >= ty && my < ty + TILE_H) return i;
        }
        return -1;
    }

    @Override
    public String tooltipAt(double mx, double my) {
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int i = tileAt(mx, my);
        if (button == 0 && i >= 0) {
            String id = everyone.get(i).id();
            if (!picked.remove(id)) picked.add(id);
            return true;
        }
        return ui.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (my >= gridY && my < gridY + ROWS * TILE_H && maxScroll() > 0) {
            scrollTo = Math.clamp(scrollTo - (float) delta * TILE_H, 0f, maxScroll());
            return true;
        }
        return ui.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int modifiers) {
        if (keyCode == KeyCodes.ENTER) {
            confirm();   // Enter 等于点保存;存不了就不动
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
