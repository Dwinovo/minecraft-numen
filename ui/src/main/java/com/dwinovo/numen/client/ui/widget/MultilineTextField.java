package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.KeyCodes;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.WrappedText;

import java.util.function.Consumer;

/**
 * 多行文本输入(人格正文)。完整编辑体验:
 * 软换行(空格优先断行,CJK 逐字断)、光标四向移动(上下按目标列记忆)、
 * Home/End(行首尾)与 Ctrl+Home/End(全文首尾)、Shift+方向键与拖选选区、
 * Ctrl+A/C/X/V(粘贴保留换行)、回车换行、滚轮/自动滚动(光标始终可见)、
 * 占位符、可选长度上限。
 *
 * <p>换行几何({@link WrappedText},与聊天输入框的多行模式同一份)依赖画布度量,首次 render 之后才有;
 * 之前的按键操作按纯换行符分行退化处理(实际交互顺序里 render 永远先来)。
 */
public final class MultilineTextField extends Widget {

    private final StringBuilder value = new StringBuilder();
    private final Consumer<String> onChange;
    private String placeholder = "";
    private int maxLength = Integer.MAX_VALUE;

    private int cursor;
    /** 选区锚点;与 cursor 相等 = 无选区。 */
    private int anchor;
    private String error;
    /** 可选:本字段的标签,出错时让位(与单行 TextField 同制)。 */
    private Label labelWidget;
    /** 垂直移动的目标横坐标(px,-1=未设);上下键连按沿同一列走,横向操作重置。 */
    private int goalX = -1;
    private int scrollY;
    private boolean dragging;

    /** 最近一次 render 的画布——度量真源(MC 字体宽度渲染/交互一致)。 */
    private IDrawSurface measure;
    /** 折行几何(每行一段 [start,end),不含换行符;永远至少一行)。 */
    private WrappedText layout = WrappedText.of("", 0, null);
    private boolean dirty = true;
    private int wrapW = -1;

    public MultilineTextField(String initial, Consumer<String> onChange) {
        if (initial != null) value.append(initial);
        this.cursor = value.length();
        this.anchor = cursor;
        this.onChange = onChange;
    }

    public MultilineTextField placeholder(String text) {
        this.placeholder = text == null ? "" : text;
        return this;
    }

    /** 认领标签:出错时自动收起它,免得两串文字在同一行叠着。 */
    public MultilineTextField withLabel(Label label) {
        this.labelWidget = label;
        return this;
    }

    public MultilineTextField maxLength(int max) {
        this.maxLength = max > 0 ? max : Integer.MAX_VALUE;
        return this;
    }

    public String value() { return value.toString(); }

    public void setValue(String v) {
        value.setLength(0);
        if (v != null) value.append(v);
        cursor = Math.min(cursor, value.length());
        anchor = cursor;
        dirty = true;
    }

    public int cursor() { return cursor; }

    /** 当前选中的文本(无选区 = 空串)。 */
    public String selectedText() {
        return value.substring(selMin(), selMax());
    }

    /** 内联校验错误:红边 + 标签行右侧红字,用户一开始输入即自动清除。 */
    public void setError(String message) {
        this.error = message == null || message.isBlank() ? null : message;
    }

    public boolean hasError() { return error != null; }

    @Override
    public boolean focusable() { return true; }

    // ---- 渲染 ----

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        this.measure = s;
        if (wrapW != innerW()) {
            wrapW = innerW();
            dirty = true;
        }
        reflowIfNeeded();

        // 统一的框:描边+内衬底;聚焦/错误只换描边色(与单行 TextField 同制)。
        int border = error != null ? c.danger() : isFocused() ? c.accent() : c.inputBorder();
        NumenStyle.box(s, x, y, w, h, c.inputBg(), border);
        if (labelWidget != null) labelWidget.setVisible(error == null);   // 出错时标签让位
        if (error != null) {
            s.drawText(error, x + w - s.textWidth(error), y - 10, c.danger(), false);
        }

        if (value.isEmpty()) {
            // 空着就给占位,聚焦着也给——打第一个字才让开(和单行输入框一样)
            s.drawText(placeholder, x + NumenStyle.FIELD_PAD, y + NumenStyle.FIELD_PAD,
                    c.textMuted(), false);
            if (!isFocused()) return;
        }

        clampScroll();
        int pitch = pitch();
        int selMin = selMin(), selMax = selMax();
        s.pushScissor(x, y, w, h);
        int first = Math.max(0, scrollY / pitch);
        int last = Math.min(layout.lineCount() - 1, (scrollY + viewH() - 1) / pitch);
        for (int i = first; i <= last; i++) {
            int lineStart = layout.start(i), lineEnd = layout.end(i);
            int ly = lineTop(i);
            if (selMax > selMin) {   // 选区底色先画,逐行裁到本行跨度
                int a = Math.max(lineStart, selMin), b = Math.min(lineEnd, selMax);
                if (a < b || (selMin <= lineEnd && selMax > lineEnd && layout.endsSoft(i))) {
                    int sx = x + NumenStyle.FIELD_PAD + layout.widthOf(lineStart, Math.max(a, lineStart));
                    int ex = a < b ? sx + layout.widthOf(a, b) : sx;
                    if (selMax > lineEnd) {
                        ex = Math.max(ex, x + NumenStyle.FIELD_PAD + layout.widthOf(lineStart, lineEnd) + 3);
                    }
                    s.fillRect(sx, ly, Math.max(2, ex - sx), pitch,
                            (c.accent() & 0x00FFFFFF) | 0x40000000);
                }
            }
            s.drawText(layout.line(i), x + NumenStyle.FIELD_PAD, ly + 1, c.textPrimary(), false);
        }

        if (isFocused() && (nowMs / 500) % 2 == 0) {   // 光标 1Hz 闪烁
            int line = cursorLine();
            int cx = x + NumenStyle.FIELD_PAD + layout.xOf(cursor);
            s.fillRect(cx, lineTop(line), 1, pitch - 1, c.textPrimary());
        }
        s.popScissor();

        int contentH = layout.lineCount() * pitch;
        if (contentH > viewH()) {   // 滚动拇指
            int thumbH = Math.max(8, viewH() * viewH() / contentH);
            int thumbY = y + 2 + (viewH() - thumbH) * scrollY / (contentH - viewH());
            s.fillRect(x + w - NumenStyle.SCROLLBAR_W - 1, thumbY, NumenStyle.SCROLLBAR_W,
                    thumbH, c.divider());
        }
    }

    // ---- 鼠标 ----

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) return false;
        cursor = indexAt(mx, my);
        anchor = cursor;   // 点击收起选区,拖动再展开
        goalX = -1;
        dragging = true;
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, double dx, double dy) {
        if (!dragging) return false;
        cursor = indexAt(mx, my);   // 锚点不动 = 拖选
        ensureCursorVisible();
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean was = dragging;
        dragging = false;
        return was;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!contains(mx, my)) return false;
        int contentH = layout.lineCount() * pitch();
        if (contentH <= viewH()) return false;
        scrollY = clampInt((int) Math.round(scrollY - delta * pitch() * 2), 0, contentH - viewH());
        return true;
    }

    // ---- 键盘 ----

    @Override
    public boolean charTyped(char ch) {
        if (ch < ' ') return false;
        insert(String.valueOf(ch));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int modifiers) {
        boolean shift = (modifiers & 0x1) != 0;
        if (KeyCodes.ctrl(modifiers)) {
            switch (keyCode) {
                case KeyCodes.KEY_A -> {
                    anchor = 0;
                    cursor = value.length();
                    return true;
                }
                case KeyCodes.KEY_C -> {
                    // 无选区退化为复制全文(与单行 TextField 同语义)。
                    if (root != null) {
                        root.copyToClipboard(hasSelection() ? selectedText() : value.toString());
                    }
                    return true;
                }
                case KeyCodes.KEY_X -> {
                    if (hasSelection() && root != null) {
                        root.copyToClipboard(selectedText());
                        deleteSelection();
                        fireChange();
                    }
                    return true;
                }
                case KeyCodes.KEY_V -> {
                    String paste = root == null ? "" : root.clipboard();
                    if (paste != null && !paste.isEmpty()) {
                        // 多行组件保留换行(单行才清洗);其余控制字符滤掉。
                        insert(paste.replace("\r\n", "\n").replace('\r', '\n')
                                .replaceAll("[\\x00-\\x09\\x0B-\\x1F]", ""));
                    }
                    return true;
                }
                case KeyCodes.HOME -> {
                    moveCursor(0, shift);
                    return true;
                }
                case KeyCodes.END -> {
                    moveCursor(value.length(), shift);
                    return true;
                }
                default -> { }
            }
        }
        switch (keyCode) {
            case KeyCodes.ENTER -> {
                insert("\n");
                return true;
            }
            case KeyCodes.BACKSPACE -> {
                if (hasSelection()) {
                    deleteSelection();
                    fireChange();
                } else if (cursor > 0) {
                    value.deleteCharAt(--cursor);
                    anchor = cursor;
                    dirty = true;
                    fireChange();
                }
                afterEdit();
                return true;
            }
            case KeyCodes.DELETE -> {
                if (hasSelection()) {
                    deleteSelection();
                    fireChange();
                } else if (cursor < value.length()) {
                    value.deleteCharAt(cursor);
                    dirty = true;
                    fireChange();
                }
                afterEdit();
                return true;
            }
            case KeyCodes.LEFT -> {
                if (hasSelection() && !shift) {
                    moveCursor(selMin(), false);   // 无 Shift 的方向键先收起选区到边缘
                } else {
                    moveCursor(Math.max(0, cursor - 1), shift);
                }
                return true;
            }
            case KeyCodes.RIGHT -> {
                if (hasSelection() && !shift) {
                    moveCursor(selMax(), false);
                } else {
                    moveCursor(Math.min(value.length(), cursor + 1), shift);
                }
                return true;
            }
            case KeyCodes.UP -> {
                verticalMove(-1, shift);
                return true;
            }
            case KeyCodes.DOWN -> {
                verticalMove(1, shift);
                return true;
            }
            case KeyCodes.HOME -> {
                moveCursor(layout.start(cursorLine()), shift);
                return true;
            }
            case KeyCodes.END -> {
                moveCursor(layout.end(cursorLine()), shift);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ---- 编辑内核 ----

    private boolean hasSelection() { return anchor != cursor; }

    private int selMin() { return Math.min(anchor, cursor); }

    private int selMax() { return Math.max(anchor, cursor); }

    private void deleteSelection() {
        int a = selMin(), b = selMax();
        value.delete(a, b);
        cursor = a;
        anchor = a;
        dirty = true;
    }

    private void insert(String text) {
        if (hasSelection()) deleteSelection();
        int room = maxLength - value.length();
        if (room <= 0) return;
        if (text.length() > room) text = text.substring(0, room);
        value.insert(cursor, text);
        cursor += text.length();
        anchor = cursor;
        dirty = true;
        fireChange();
        afterEdit();
    }

    /** 移动光标;{@code keepAnchor}=Shift 按住(扩展选区),否则锚点跟走。 */
    private void moveCursor(int to, boolean keepAnchor) {
        cursor = clampInt(to, 0, value.length());
        if (!keepAnchor) anchor = cursor;
        goalX = -1;
        ensureCursorVisible();
    }

    /** 上下移动:目标列(px)在连续垂直移动间记忆,穿过短行不丢列。 */
    private void verticalMove(int dir, boolean keepAnchor) {
        reflowIfNeeded();
        int line = cursorLine();
        if (goalX < 0) goalX = layout.xOf(cursor);
        int target = line + dir;
        if (target < 0) {
            cursor = 0;
        } else if (target >= layout.lineCount()) {
            cursor = value.length();
        } else {
            cursor = layout.indexAtX(target, goalX);
        }
        if (!keepAnchor) anchor = cursor;
        ensureCursorVisible();
    }

    private void afterEdit() {
        goalX = -1;
        reflowIfNeeded();
        ensureCursorVisible();
    }

    private void fireChange() {
        error = null;   // 用户开始修改即撤下错误标记
        if (onChange != null) onChange.accept(value.toString());
    }

    // ---- 换行几何 ----

    /** 夹紧(Math.clamp 是 Java 21 的;组件库按 17 编译,十一分支同源消费)。 */
    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private int innerW() { return w - NumenStyle.FIELD_PAD * 2 - NumenStyle.SCROLLBAR_W - 1; }

    private int viewH() { return h - 4; }

    private int pitch() { return (measure == null ? 9 : measure.lineHeight()) + 2; }

    private int lineTop(int line) { return y + 2 + line * pitch() - scrollY; }

    private void reflowIfNeeded() {
        if (!dirty) return;
        dirty = false;
        layout = WrappedText.of(value.toString(), innerW(), measure == null ? null : measure::textWidth);
    }

    /** 光标所在的视觉行:软换行边界上的光标归下一行行首(通用编辑器行为)。 */
    private int cursorLine() {
        reflowIfNeeded();
        return layout.lineOf(cursor);
    }

    /** 屏幕坐标 → 文本下标(行外点击夹到最近行/行首行尾)。 */
    private int indexAt(double mx, double my) {
        reflowIfNeeded();
        int line = clampInt((int) Math.floor((my - y - 2 + scrollY) / (double) pitch()),
                0, layout.lineCount() - 1);
        return layout.indexAtX(line, (int) (mx - x - NumenStyle.FIELD_PAD));
    }

    private void clampScroll() {
        int contentH = layout.lineCount() * pitch();
        scrollY = clampInt(scrollY, 0, Math.max(0, contentH - viewH()));
    }

    /** 编辑/移动后自动滚动:光标行完整落在视口内。 */
    private void ensureCursorVisible() {
        reflowIfNeeded();
        int line = cursorLine();
        int top = line * pitch();
        if (top < scrollY) scrollY = top;
        int bottom = top + pitch();
        if (bottom > scrollY + viewH()) scrollY = bottom - viewH();
    }
}
