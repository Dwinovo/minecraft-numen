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

    private final UiRoot ui = new UiRoot();
    private final Host host;

    private TextField field;
    private Button compactBtn, micBtn, sendBtn, stopBtn;
    private String draft = "";
    private ResourceLocation micIcon;
    private final ResourceLocation iconCompact, iconMic, iconStop, iconSend;

    /** 输入框自己的几何(弹层要贴着它的上边长)。 */
    private int fieldX, fieldY, fieldW;
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
        }).placeholder(host.hint()));
        field.setBounds(inX, y, inW, h);
        fieldX = inX;
        fieldY = y;
        fieldW = inW;

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
        field.setEnabled(!locked);
        field.placeholder(host.hint());
        compactBtn.setEnabled(!locked && host.canCompact());
        micBtn.setEnabled(!locked);
        sendBtn.setEnabled(!locked);
        stopBtn.setEnabled(host.canAbort());   // 叫停不随锁定禁用:急刹车永远可用
    }

    // ---- 宿主转发面 ----

    public void render(GuiGraphics g, int mouseX, int mouseY, long nowMs, NumenTheme.Colors c) {
        refreshEnablement();
        IDrawSurface s = new McDrawSurface(g, Minecraft.getInstance().font);
        ui.render(s, c, mouseX, mouseY, nowMs);
        // 弹层最后画:它要压在对话流上面。
        if (popupOpen()) {
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
        return ui.charTyped(ch);
    }

    public boolean isFieldFocused() {
        return field != null && field.isFocused();
    }

    // ---- 内部 ----

    private void send() {
        if (field == null || host.inputLocked()) return;
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
