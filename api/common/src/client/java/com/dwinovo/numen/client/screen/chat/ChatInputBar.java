package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.client.command.CommandCandidate;
import com.dwinovo.numen.client.command.CommandCompletionPolicy;
import com.dwinovo.numen.client.command.CommandRegistry;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.RoundRect;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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
    }

    private static final int BTN_W = 22;
    private static final int GAP = 4;
    private static final int SUG_ROW_H = 13;
    private static final int SUG_MAX_ROWS = 8;
    private static final int SUG_PAD = 4;
    private static final int SUG_MAX_W = 260;

    private final UiRoot ui = new UiRoot();
    private final Host host;

    private TextField field;
    private Button compactBtn, micBtn, sendBtn, stopBtn;
    private String draft = "";
    private List<CommandCandidate> suggestions = List.of();
    private boolean suggestionsOpen;
    private int selectedSuggestion;
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
        refreshSuggestions();
    }

    /** 录音中:麦克风图标换成停止方块——同一颗键,两种含义都一眼可读。 */
    public void setRecording(boolean recording) {
        micIcon = recording ? iconStop : iconMic;
    }

    public void build(int x, int y, int w, int h) {
        if (field != null) draft = field.value();   // 重建不丢已输入的文字
        ui.clear();
        suggestions = List.of();
        suggestionsOpen = false;
        selectedSuggestion = 0;

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
            refreshSuggestions();
        }).placeholder(host.hint()));
        field.setBounds(inX, y, inW, h);

        sendBtn = ui.add(iconButton(iconSend, "numen.chat.send",
                Button.Style.ACCENT, this::send));
        sendBtn.setBounds(inX + inW + GAP, y, BTN_W, h);
        stopBtn = ui.add(iconButton(iconStop, "numen.chat.tip.stop",
                Button.Style.NORMAL, host::onAbort));
        stopBtn.setBounds(inX + inW + GAP * 2 + BTN_W, y, BTN_W, h);

        ui.requestFocus(field);   // 开屏即可打字
        refreshSuggestions();
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
        if (field != null && field.isFocused() && field.value().startsWith("/")
                && suggestions.isEmpty()) {
            refreshSuggestions();
        }
        ui.render(new McDrawSurface(g, Minecraft.getInstance().font), c, mouseX, mouseY, nowMs);
        renderSuggestions(g, Minecraft.getInstance().font);
    }

    /** 悬停的按钮提示文案(宿主自行绘制 tooltip:定位与样式是宿主的事)。 */
    public String tooltipAt(double mx, double my) {
        for (Button b : new Button[]{compactBtn, micBtn, sendBtn, stopBtn}) {
            if (b != null && b.enabled() && b.contains(mx, my)) return b.tooltip();
        }
        return null;
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && suggestionsOpen && field != null && field.isFocused()) {
            int row = suggestionRowAt(mx, my);
            if (row >= 0) {
                selectedSuggestion = row;
                completeSelected();
                return true;
            }
        }
        return ui.mouseClicked(mx, my, button);
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        if (field != null && field.isFocused() && suggestionsOpen && !suggestions.isEmpty()) {
            switch (keyCode) {
                case com.dwinovo.numen.client.ui.KeyCodes.UP -> {
                    moveSelection(-1);
                    return true;
                }
                case com.dwinovo.numen.client.ui.KeyCodes.DOWN -> {
                    moveSelection(1);
                    return true;
                }
                case com.dwinovo.numen.client.ui.KeyCodes.TAB -> {
                    int direction = (modifiers & 0x1) != 0 ? -1 : 1;
                    CommandCompletionPolicy.TabDecision decision =
                            CommandCompletionPolicy.tab(field.value(), suggestions,
                                    selectedSuggestion, direction);
                    applyCompletion(decision.text(), decision.selectedIndex());
                    return true;
                }
                case com.dwinovo.numen.client.ui.KeyCodes.ESCAPE -> {
                    closeSuggestions();
                    return true;
                }
                case com.dwinovo.numen.client.ui.KeyCodes.ENTER -> {
                    CommandCompletionPolicy.EnterDecision decision =
                            CommandCompletionPolicy.enter(field.value(), suggestions,
                                    selectedSuggestion);
                    if (decision.send()) {
                        closeSuggestions();
                        send();
                    } else {
                        applyCompletion(decision.text(), selectedSuggestion);
                    }
                    return true;
                }
                default -> { /* fall through to the field */ }
            }
        }
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

    private void refreshSuggestions() {
        refreshSuggestions(-1);
    }

    private void refreshSuggestions(int preferredIndex) {
        if (field == null) {
            suggestions = List.of();
            suggestionsOpen = false;
            selectedSuggestion = 0;
            return;
        }
        String value = field.value();
        if (value == null || !value.startsWith("/")) {
            suggestions = List.of();
            suggestionsOpen = false;
            selectedSuggestion = 0;
            return;
        }
        suggestions = CommandRegistry.instance().candidates(value);
        selectedSuggestion = preferredIndex >= 0 && preferredIndex < suggestions.size()
                ? preferredIndex : 0;
        suggestionsOpen = !suggestions.isEmpty() && field.isFocused();
    }

    private void closeSuggestions() {
        suggestionsOpen = false;
    }

    private void moveSelection(int delta) {
        if (suggestions.isEmpty()) return;
        selectedSuggestion = Math.floorMod(selectedSuggestion + delta, suggestions.size());
    }

    private void completeSelected() {
        if (suggestions.isEmpty()) return;
        int index = Math.max(0, Math.min(selectedSuggestion, suggestions.size() - 1));
        CommandCandidate candidate = suggestions.get(index);
        applyCompletion(candidate.completionText(), index);
    }

    private void applyCompletion(String text, int preferredIndex) {
        if (field == null) return;
        field.setValue(text);
        field.moveCursorToEnd();
        draft = text;
        refreshSuggestions(preferredIndex);
    }

    private void renderSuggestions(GuiGraphics g, Font font) {
        if (!suggestionsOpen || suggestions.isEmpty() || field == null || !field.isFocused()) return;
        UiTheme th = UiTheme.current();
        int sx = suggestionX();
        int sw = suggestionW();
        int rows = suggestionRows();
        int sh = suggestionH();
        int sy = suggestionY();
        RoundRect.card(g, sx, sy, sx + sw, sy + sh, 4, th.surface(), th.surfaceBorder());
        for (int i = 0; i < rows; i++) {
            CommandCandidate candidate = suggestions.get(i);
            int ry = sy + SUG_PAD + i * SUG_ROW_H;
            if (i == selectedSuggestion) {
                RoundRect.fill(g, sx + 2, ry, sx + sw - 2, ry + SUG_ROW_H, 3, th.chipFill());
            }
            int textColor = i == selectedSuggestion ? th.text() : th.textDim();
            Nb.text(g, font, candidate.command(), sx + 6, ry + 2, textColor);
            int cmdW = font.width(candidate.command());
            int descMax = Math.max(20, sw - cmdW - 14);
            Nb.text(g, font, clip(font, candidate.description(), descMax),
                    sx + 8 + cmdW, ry + 2, th.faint());
        }
    }

    private int suggestionX() {
        return field == null ? 0 : field.x();
    }

    private int suggestionW() {
        return field == null ? 0 : Math.min(SUG_MAX_W, field.w());
    }

    private int suggestionRows() {
        return Math.min(suggestions.size(), SUG_MAX_ROWS);
    }

    private int suggestionH() {
        return suggestionRows() * SUG_ROW_H + SUG_PAD * 2;
    }

    private int suggestionY() {
        return field == null ? 0 : Math.max(0, field.y() - suggestionH() - 3);
    }

    private int suggestionRowAt(double mx, double my) {
        if (suggestions.isEmpty()) return -1;
        int sx = suggestionX();
        int sw = suggestionW();
        int sy = suggestionY() + SUG_PAD;
        int rows = suggestionRows();
        if (mx < sx || mx >= sx + sw || my < sy || my >= sy + rows * SUG_ROW_H) return -1;
        int row = (int) ((my - sy) / SUG_ROW_H);
        return row >= 0 && row < rows ? row : -1;
    }

    private static String clip(Font font, String text, int maxW) {
        if (text == null || text.isBlank()) return "";
        if (font.width(text) <= maxW) return text;
        String s = text;
        while (s.length() > 1 && font.width(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

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
