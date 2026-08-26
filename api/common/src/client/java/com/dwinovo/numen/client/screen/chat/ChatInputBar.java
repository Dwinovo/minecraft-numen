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
import java.util.Set;

/**
 * 聊天输入行——NumenUI 版的瓤:[麦克风] 输入框 [发送][叫停]。
 * 图标钮走组件库的 Button 图标形态(贴图由本层注入,组件库不认识贴图);
 * 输入框走 TextField(Enter 发送)。锁定态(外接大脑模式)整排禁用只留叫停
 * ——那是主人的急刹车,外部 AI 抽风时更需要它。
 *
 * <p>斜杠命令整个归这条输入行:补全弹层、面板类命令(/skills)在原位开面板、发送时
 * 的拦截("是 / 开头?在本地跑完,不往下走",见 {@code ChatCommands})。宿主只收到
 * 两样东西:真正要说给她的话({@link Host#onSend})和命令的回话
 * ({@link Host#onCommandReply})。G 面板和 Y 快捷对话用的是同一条输入行,差别只在
 * 宿主和带哪几颗键({@link Key}):快捷对话不带麦克风——快捷语音有自己的按住说话键,
 * 不搞两条语音路。
 */
public final class ChatInputBar {

    /** 输入框右侧可选的几颗键;顺序即布局。 */
    public enum Key { MIC, SEND, STOP }

    private static final ResourceLocation ICON_MIC = icon("icon_mic");
    private static final ResourceLocation ICON_SEND = icon("icon_send");
    private static final ResourceLocation ICON_STOP = icon("icon_stop");

    private static ResourceLocation icon(String name) {
        return ResourceLocation.fromNamespaceAndPath(com.dwinovo.numen.Constants.MOD_ID, name);
    }

    /** 宿主回调面:说话/麦克风/叫停,以及"这几颗键此刻可不可按"。 */
    public interface Host {
        /** 主人真要说给她的话(斜杠命令不会走到这儿,已在输入行本地跑完)。 */
        void onSend(String text);

        /** 只有带 {@link Key#MIC} 的输入行会调。 */
        default void onMicToggle() {}

        void onAbort();

        boolean canAbort();

        /** 输入框占位文案(随麦克风状态变)。 */
        String hint();

        /** 这条输入行对着的那位的大脑;null = 没选同伴(不补全、不跑命令)。 */
        com.dwinovo.numen.client.agent.EntityAgentLoop loop();

        /** 斜杠命令跑完回给主人的话;null = 这条命令不吭声(或已在原位开了面板)。画在哪、留多久是宿主的事。 */
        void onCommandReply(String reply);
    }

    private static final int BTN_W = 22;
    private static final int GAP = 4;
    /** 输入框里 {@code /命令} 那一截的颜色。定死不跟主题走——它标的是"这是命令不是话"
     *  这件事,换主题不该让它变得像普通文字。 */
    private static final int CMD_COLOR = 0xFFA6AEE9;

    private final UiRoot ui = new UiRoot();
    private final Host host;
    /** 这条输入行带哪几颗键。 */
    private final Set<Key> wanted;

    private TextField field;
    /** 缺席的键为 null(不在 {@link #wanted} 里)。 */
    private Button micBtn, sendBtn, stopBtn;
    /** 右侧那一串键,顺序即布局。 */
    private Button[] keys = new Button[0];
    private String draft = "";
    private ResourceLocation micIcon = ICON_MIC;

    /** 输入框自己的几何(弹层贴它上边长,面板占它的位)。 */
    private int fieldX, fieldY, fieldW, fieldH;
    /** 开着的选择面板;非 null 时它<b>取代</b>输入框,键盘整个归它。 */
    /** 贴着输入框弹出来的那一层。装什么由命令决定(名单、读数卡…),见 Popup。 */
    private com.dwinovo.numen.client.ui.widget.Popup panel;
    /** 当前补全候选。空 = 不弹层。 */
    private List<Completion> candidates = List.of();
    private int selected;
    /** 主人按了 Esc 收起弹层;一改文字就复位——收的是"这次",不是这个功能。 */
    private boolean dismissed;

    public ChatInputBar(Host host, Set<Key> keys) {
        this.host = host;
        this.wanted = Set.copyOf(keys);
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
        micIcon = recording ? ICON_STOP : ICON_MIC;
    }

    public void build(int x, int y, int w, int h) {
        if (field != null) draft = field.value();   // 重建不丢已输入的文字
        ui.clear();

        micBtn = wanted.contains(Key.MIC) ? ui.add(iconButton(null, "numen.chat.tip.mic",
                Button.Style.NORMAL, host::onMicToggle)) : null;
        sendBtn = wanted.contains(Key.SEND) ? ui.add(iconButton(ICON_SEND, "numen.chat.send",
                Button.Style.ACCENT, this::send)) : null;
        stopBtn = wanted.contains(Key.STOP) ? ui.add(iconButton(ICON_STOP, "numen.chat.tip.stop",
                Button.Style.NORMAL, host::onAbort)) : null;
        // 顺序即布局:输入框吃掉左边剩下的,这一串靠右排。加减一颗键只改这个数组,
        // 不用回来重算"左几右几"那两个常数。
        keys = java.util.stream.Stream.of(micBtn, sendBtn, stopBtn)
                .filter(java.util.Objects::nonNull).toArray(Button[]::new);

        int inW = w - (BTN_W + GAP) * keys.length;
        field = ui.add(new TextField(draft, v -> {
            draft = v;
            refreshCandidates();
        }).placeholder(host.hint())
                .leadingToken(com.dwinovo.numen.client.command.ChatCommands.PREFIX, CMD_COLOR));
        field.setBounds(x, y, inW, h);
        fieldX = x;
        fieldY = y;
        fieldW = inW;
        fieldH = h;
        for (int i = 0; i < keys.length; i++) {
            keys[i].setBounds(x + inW + GAP + i * (BTN_W + GAP), y, BTN_W, h);
        }

        ui.requestFocus(field);   // 开屏即可打字
        refreshCandidates();
        refreshEnablement();
    }

    /** 每帧同步可按性与占位文案:叫停的可用性是活的。 */
    public void refreshEnablement() {
        if (field == null) return;
        boolean paged = panel != null;
        // 面板在场时输入框让位(它就摆在输入框那格),旁边几颗键跟着停手——
        // 叫停除外:那是主人的急刹车,任何时候都得能按。
        field.setVisible(!paged);
        field.setEnabled(!paged);
        field.placeholder(host.hint());
        if (micBtn != null) micBtn.setEnabled(!paged);
        if (sendBtn != null) sendBtn.setEnabled(!paged);
        if (stopBtn != null) stopBtn.setEnabled(host.canAbort());
    }

    // ---- 选择面板(取代输入框的那一层) ----

    /**
     * 打开一个面板。它摆在输入框那一格、底边对齐,<b>往上</b>长得更高——一次要看好几行,
     * 而下面没有地方。
     */
    public void openPopup(com.dwinovo.numen.client.ui.widget.Popup popup) {
        if (popup == null || field == null) return;
        panel = popup;
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
        for (Button b : keys) {
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
                    // 回车 = 就要选中这条,现在执行。想接着打参数请按 Tab。
                    // 两颗键分工明确之后,"补全了没有"就不再影响回车干什么了。
                    fillSelected();
                    send();
                    return true;
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
                && field != null && field.isFocused();
    }

    /** 文字变了就重算候选,并把 Esc 的收起复位。 */
    private void refreshCandidates() {
        dismissed = false;
        String text = field != null ? field.value() : draft;
        var loop = host.loop();
        candidates = loop == null ? List.of()
                : com.dwinovo.numen.client.command.ChatCommands.complete(loop, text == null ? "" : text);
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
        if (field == null || panel != null) return;
        String text = field.value() == null ? "" : field.value().trim();
        if (text.isEmpty()) return;
        // 斜杠命令是主人对客户端说的话:在本地跑完就结束,不往下走。所以它不过宿主的
        // 发言闸门——查技能、看清单这些事没有理由要求先配好 API key。
        var loop = host.loop();
        if (loop != null && com.dwinovo.numen.client.command.ChatCommands.isCommand(text)) {
            // 面板类命令:多余的参数不理会——它要的不是参数,是一个能上下选的界面。
            var page = com.dwinovo.numen.client.command.ChatCommands.popupFor(loop, text);
            if (page != null) {
                openPopup(page);
                host.onCommandReply(null);
                return;
            }
            String reply = com.dwinovo.numen.client.command.ChatCommands.dispatch(loop, text);
            setText("");
            host.onCommandReply(reply);
            return;
        }
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
                        mc.graphics().blitSprite(net.minecraft.client.renderer.RenderType::guiTextured, icon, ix, iy, size, size);
                    }
                })
                .tooltip(t(tipKey));
    }

    private static String t(String key) {
        return Component.translatable(key).getString();
    }
}
