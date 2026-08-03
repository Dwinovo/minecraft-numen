package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.client.ui.IDrawSurface;
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

        field = ui.add(new TextField(draft, v -> draft = v).placeholder(host.hint()));
        field.setBounds(inX, y, inW, h);

        sendBtn = ui.add(iconButton(iconSend, "numen.chat.send",
                Button.Style.ACCENT, this::send));
        sendBtn.setBounds(inX + inW + GAP, y, BTN_W, h);
        stopBtn = ui.add(iconButton(iconStop, "numen.chat.tip.stop",
                Button.Style.NORMAL, host::onAbort));
        stopBtn.setBounds(inX + inW + GAP * 2 + BTN_W, y, BTN_W, h);

        ui.requestFocus(field);   // 开屏即可打字
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
        ui.render(new McDrawSurface(g, Minecraft.getInstance().font), c, mouseX, mouseY, nowMs);
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
        if (keyCode == com.dwinovo.numen.client.ui.KeyCodes.ENTER && field != null
                && field.isFocused()) {
            send();
            return true;
        }
        return ui.keyPressed(keyCode, modifiers);
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
