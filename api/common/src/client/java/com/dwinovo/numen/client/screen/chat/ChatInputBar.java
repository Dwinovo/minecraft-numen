package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.client.command.Completion;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.KeyCodes;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 聊天输入行——NumenUI 版的瓤:[压缩][麦克风] 输入框 [发送][叫停]。
 * 四颗图标钮走组件库的 Button 图标形态(贴图由本层注入,组件库不认识贴图);
 * 输入框走 TextField(Enter 发送)。锁定态(外接大脑模式)整排禁用只留叫停
 * ——那是主人的急刹车,外部 AI 抽风时更需要它。
 */
public final class ChatInputBar {

    /** 宿主回调面:发送/麦克风/压缩/叫停,以及"这几颗键此刻可不可按"。 */
    public interface Host {
        void onSend(String text);

        void onMicToggle();

        void onCompact();

        void onAbort();

        boolean canCompact();

        boolean canAbort();

        /** 外接大脑模式:发言入口整排锁死(叫停不锁)。 */
        boolean inputLocked();

        /** 输入框占位文案(随锁定态/麦克风状态变)。 */
        String hint();

        /** 这串输入对应的斜杠命令补全候选;空 = 不弹层。输入行不认识命令是什么。 */
        List<Completion> completions(String text);
    }

    private static final int BTN_W = 22;
    private static final int GAP = 4;
    /** 输入框里 {@code /命令} 那一截的颜色。定死不跟主题走——它标的是"这是命令不是话"
     *  这件事,换主题不该让它变得像普通文字。 */
    private static final int CMD_COLOR = 0xFFA6AEE9;

    private final UiRoot ui = new UiRoot();
    private final Host host;

    private TextField field;
    private Button compactBtn, micBtn, sendBtn, stopBtn;
    private String draft = "";
    private ResourceLocation micIcon;
    private final ResourceLocation iconCompact, iconMic, iconStop, iconSend;

    /** 输入框自己的几何(弹层贴它上边长,面板占它的位)。 */
    private int fieldX, fieldY, fieldW, fieldH;
    /** 开着的选择面板;非 null 时它<b>取代</b>输入框,键盘整个归它。 */
    private com.dwinovo.numen.client.ui.widget.SelectPanel panel;
    /** 当前补全候选。空 = 不弹层。 */
    private List<Completion> candidates = List.of();
    private int selected;
    /** 主人按了 Esc 收起弹层;一改文字就复位——收的是"这次",不是这个功能。 */
    private boolean dismissed;

    public ChatInputBar(Host host, ResourceLocation iconCompact, ResourceLocation iconMic,
                        ResourceLocation iconSend, ResourceLocation iconStop) {
        this.host = host;
        this.iconCompact = iconCompact;
        this.iconMic = iconMic;
        this.iconSend = iconSend;
        this.iconStop = iconStop;
        this.micIcon = iconMic;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
    }

    /** 输入框内容(切换同伴时宿主取走暂存,回来再 setText 放回)。 */
    public String text() {
        return field != null ? field.value() : draft;
    }

    public void setText(String text) {
        draft = text == null ? "" : text;
        if (field != null) field.setValue(draft);
        refreshCandidates();
    }

    /** 录音中:麦克风图标换成停止方块——同一颗键,两种含义都一眼可读。 */
    public void setRecording(boolean recording) {
        micIcon = recording ? iconStop : iconMic;
    }

    public void build(int x, int y, int w, int h) {
        if (field != null) draft = field.value();   // 重建不丢已输入的文字
        ui.clear();

        int inX = x + (BTN_W + GAP) * 2;
        int inW = w - (BTN_W + GAP) * 4;

        compactBtn = ui.add(iconButton(iconCompact, "numen.chat.tip.compact",
                Button.Style.NORMAL, host::onCompact));
        compactBtn.setBounds(x, y, BTN_W, h);
        micBtn = ui.add(iconButton(null, "numen.chat.tip.mic",
                Button.Style.NORMAL, host::onMicToggle));
        micBtn.setBounds(x + BTN_W + GAP, y, BTN_W, h);

        field = ui.add(new TextField(draft, v -> {
            draft = v;
            refreshCandidates();
        }).placeholder(host.hint())
                .leadingToken(com.dwinovo.numen.client.command.ChatCommands.PREFIX, CMD_COLOR));
        field.setBounds(inX, y, inW, h);
        fieldX = inX;
        fieldY = y;
        fieldW = inW;
        fieldH = h;

        sendBtn = ui.add(iconButton(iconSend, "numen.chat.send",
                Button.Style.ACCENT, this::send));
        sendBtn.setBounds(inX + inW + GAP, y, BTN_W, h);
        stopBtn = ui.add(iconButton(iconStop, "numen.chat.tip.stop",
                Button.Style.NORMAL, host::onAbort));
        stopBtn.setBounds(inX + inW + GAP * 2 + BTN_W, y, BTN_W, h);

        ui.requestFocus(field);   // 开屏即可打字
        refreshCandidates();
        refreshEnablement();
    }

    /** 每帧同步可按性与占位文案:压缩/叫停的可用性、锁定态都是活的。 */
    public void refreshEnablement() {
        if (field == null) return;
        boolean locked = host.inputLocked();
        boolean paged = panel != null;
        // 面板在场时输入框让位(它就摆在输入框那格),旁边几颗键跟着停手——
        // 叫停除外:那是主人的急刹车,任何时候都得能按。
        field.setVisible(!paged);
        field.setEnabled(!locked && !paged);
        field.placeholder(host.hint());
        compactBtn.setEnabled(!locked && !paged && host.canCompact());
        micBtn.setEnabled(!locked && !paged);
        sendBtn.setEnabled(!locked && !paged);
        stopBtn.setEnabled(host.canAbort());
    }

    // ---- 选择面板(取代输入框的那一层) ----

    /**
     * 打开一个面板。它摆在输入框那一格、底边对齐,<b>往上</b>长得更高——一次要看好几行,
     * 而下面没有地方。
     */
    public void openPage(com.dwinovo.numen.client.ui.widget.SelectPanel.Page page) {
        if (page == null || field == null) return;
        panel = new com.dwinovo.numen.client.ui.widget.SelectPanel(page);
        int ph = Math.max(fieldH, panel.preferredHeight());
        panel.setBounds(fieldX, fieldY + fieldH - ph, fieldW, ph);
        candidates = List.of();   // 补全弹层让位:一次只有一个东西吃键盘
        refreshEnablement();
    }

    public boolean pageOpen() {
        return panel != null;
    }

    /** 关面板回到输入框。文字清空——刚才那串 {@code /skills} 已经用过了。 */
    private void closePage() {
        panel = null;
        setText("");
        refreshEnablement();
    }

    // ---- 宿主转发面 ----

    public void render(GuiGraphics g, int mouseX, int mouseY, long nowMs, NumenTheme.Colors c) {
        refreshEnablement();
        IDrawSurface s = new McDrawSurface(g, Minecraft.getInstance().font);
        ui.render(s, c, mouseX, mouseY, nowMs);
        // 面板与弹层都最后画:它俩要压在对话流上面。同时只会有一个。
        if (panel != null) {
            panel.render(s, c, mouseX, mouseY, nowMs);
        } else if (popupOpen()) {
            CommandPopup.render(s, c, candidates, selected, fieldX, fieldY - 2, fieldW);
        }
    }

    /** 悬停的按钮提示文案(宿主自行绘制 tooltip:定位与样式是宿主的事)。 */
    public String tooltipAt(double mx, double my) {
        for (Button b : new Button[]{compactBtn, micBtn, sendBtn, stopBtn}) {
            if (b != null && b.enabled() && b.contains(mx, my)) return b.tooltip();
        }
        return null;
    }

    public boolean mouseClicked(double mx, double my, int button) {
        return ui.mouseClicked(mx, my, button);
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        // 面板在场:键盘整个归它,一个都不往下漏。Esc 是回输入框,不是关整个界面。
        if (panel != null) {
            if (keyCode == KeyCodes.ESCAPE) {
                closePage();
                return true;
            }
            panel.keyPressed(keyCode, modifiers);
            return true;
        }
        // 弹层在场时先归它:↑↓ 选、Tab 补/循环、Esc 收、回车先补再谈发送。
        if (popupOpen()) {
            switch (keyCode) {
                case KeyCodes.ESCAPE -> {
                    dismissed = true;
                    return true;
                }
                case KeyCodes.UP -> {
                    move(-1);
                    return true;
                }
                case KeyCodes.DOWN -> {
                    move(1);
                    return true;
                }
                case KeyCodes.TAB -> {
                    // 还没补上就补上;已经是它了就换下一个——同一颗键,两步都顺手。
                    if (!fillSelected()) {
                        move(1);
                        fillSelected();
                    }
                    return true;
                }
                case KeyCodes.ENTER -> {
                    // 补全优先于发送:半截命令按回车,主人要的是补完,不是把半截发出去。
                    if (fillSelected()) {
                        return true;
                    }
                }
                default -> { }
            }
        }
        if (keyCode == KeyCodes.ENTER && field != null && field.isFocused()) {
            send();
            return true;
        }
        return ui.keyPressed(keyCode, modifiers);
    }

    // ---- 补全 ----

    /** 弹层此刻该不该在。 */
    private boolean popupOpen() {
        return !dismissed && !candidates.isEmpty()
                && field != null && field.isFocused() && !host.inputLocked();
    }

    /** 文字变了就重算候选,并把 Esc 的收起复位。 */
    private void refreshCandidates() {
        dismissed = false;
        String text = field != null ? field.value() : draft;
        candidates = host.completions(text == null ? "" : text);
        selected = firstEnabled();
    }

    private int firstEnabled() {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).enabled()) return i;
        }
        return 0;
    }

    /** 选下一个可用的,到头绕回去;一个可用的都没有就不动。 */
    private void move(int dir) {
        int n = candidates.size();
        if (n == 0) return;
        for (int step = 1; step <= n; step++) {
            int i = Math.floorMod(selected + dir * step, n);
            if (candidates.get(i).enabled()) {
                selected = i;
                return;
            }
        }
    }

    /** 把选中项填进输入框。已经就是它了(或选不中)返回 false,让调用方决定下一步。 */
    private boolean fillSelected() {
        if (selected < 0 || selected >= candidates.size()) return false;
        Completion pick = candidates.get(selected);
        if (!pick.enabled() || pick.insert().equals(field.value())) return false;
        field.setValue(pick.insert());
        field.cursorToEnd();
        draft = pick.insert();
        refreshCandidates();
        return true;
    }

    public boolean charTyped(char ch) {
        // 面板在场时输入框是隐着的,打进去的字看不见也用不上——直接吞掉。
        return panel != null || ui.charTyped(ch);
    }

    public boolean isFieldFocused() {
        return field != null && field.isFocused();
    }

    // ---- 内部 ----

    private void send() {
        if (field == null || panel != null || host.inputLocked()) return;
        String text = field.value() == null ? "" : field.value().trim();
        if (text.isEmpty()) return;
        host.onSend(text);
    }

    /** 图标钮:贴图绘制由本层(允许 import MC)注入,组件库只管几何与状态色。
     *  {@code sprite} 传 null = 用活的麦克风图标(录音中换停止方块)。 */
    private Button iconButton(ResourceLocation sprite, String tipKey,
                              Button.Style style, Runnable action) {
        return new Button(t(tipKey), style, action)
                .icon(12, (s, ix, iy, size, argb) -> {
                    ResourceLocation icon = sprite != null ? sprite : micIcon;
                    if (s instanceof McDrawSurface mc) {
                        mc.graphics().blitSprite(icon, ix, iy, size, size);
                    }
                })
                .tooltip(t(tipKey));
    }

    private static String t(String key) {
        return Component.translatable(key).getString();
    }
}
