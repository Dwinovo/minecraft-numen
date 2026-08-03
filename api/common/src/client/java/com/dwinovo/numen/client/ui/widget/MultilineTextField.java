package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.KeyCodes;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 多行文本输入(人格正文/聊天输入框的地基)。完整编辑体验:
 * 软换行(空格优先断行,CJK 逐字断)、光标四向移动(上下按目标列记忆)、
 * Home/End(行首尾)与 Ctrl+Home/End(全文首尾)、Shift+方向键与拖选选区、
 * Ctrl+A/C/X/V(粘贴保留换行)、回车换行、滚轮/自动滚动(光标始终可见)、
 * 占位符、可选长度上限。
 *
 * <p>换行几何依赖画布度量,首次 render 之后才有;之前的按键操作按纯
 * 换行符分行退化处理(实际交互顺序里 render 永远先来)。
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
    /** 垂直移动的目标横坐标(px,-1=未设);上下键连按沿同一列走,横向操作重置。 */
    private int goalX = -1;
    private int scrollY;
    private boolean dragging;

    /** 最近一次 render 的画布——度量真源(MC 字体宽度渲染/交互一致)。 */
    private IDrawSurface measure;
    /** 行跨度 [start,end)(end 不含换行符);永远至少一行。 */
    private List<int[]> lines = List.of(new int[]{0, 0});
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

        s.fillRect(x, y, w, h, c.inputBg());
        int border = error != null ? c.danger() : isFocused() ? c.accent() : c.inputBorder();
        s.fillRect(x, y + h - 1, w, error != null ? 2 : 1, border);
        if (error != null) {
            s.drawText(error, x + w - s.textWidth(error), y - 10, c.danger(), false);
        }

        if (value.isEmpty() && !isFocused()) {
            s.drawText(placeholder, x + NumenStyle.FIELD_PAD, y + NumenStyle.FIELD_PAD,
                    c.textMuted(), false);
            return;
        }

        clampScroll();
        int pitch = pitch();
        int selMin = selMin(), selMax = selMax();
        s.pushScissor(x, y, w, h);
        int first = Math.max(0, scrollY / pitch);
        int last = Math.min(lines.size() - 1, (scrollY + viewH() - 1) / pitch);
        for (int i = first; i <= last; i++) {
            int[] span = lines.get(i);
            int ly = lineTop(i);
            if (selMax > selMin) {   // 选区底色先画,逐行裁到本行跨度
                int a = Math.max(span[0], selMin), b = Math.min(span[1], selMax);
                if (a < b || (selMin <= span[1] && selMax > span[1] && endsSoft(i))) {
                    int sx = x + NumenStyle.FIELD_PAD + widthOf(span[0], Math.max(a, span[0]));
                    int ex = a < b ? sx + widthOf(a, b) : sx;
                    if (selMax > span[1]) ex = Math.max(ex, x + NumenStyle.FIELD_PAD + widthOf(span[0], span[1]) + 3);
                    s.fillRect(sx, ly, Math.max(2, ex - sx), pitch,
                            (c.accent() & 0x00FFFFFF) | 0x40000000);
                }
            }
            s.drawText(value.substring(span[0], span[1]), x + NumenStyle.FIELD_PAD, ly + 1,
                    c.textPrimary(), false);
        }

        if (isFocused() && (nowMs / 500) % 2 == 0) {   // 光标 1Hz 闪烁
            int line = cursorLine();
            int cx = x + NumenStyle.FIELD_PAD + widthOf(lines.get(line)[0], cursor);
            s.fillRect(cx, lineTop(line), 1, pitch - 1, c.textPrimary());
        }
        s.popScissor();

        int contentH = lines.size() * pitch;
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
        int contentH = lines.size() * pitch();
        if (contentH <= viewH()) return false;
        scrollY = Math.clamp((long) (scrollY - delta * pitch() * 2), 0, contentH - viewH());
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
                moveCursor(lines.get(cursorLine())[0], shift);
                return true;
            }
            case KeyCodes.END -> {
                moveCursor(lines.get(cursorLine())[1], shift);
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
        cursor = Math.clamp(to, 0, value.length());
        if (!keepAnchor) anchor = cursor;
        goalX = -1;
        ensureCursorVisible();
    }

    /** 上下移动:目标列(px)在连续垂直移动间记忆,穿过短行不丢列。 */
    private void verticalMove(int dir, boolean keepAnchor) {
        reflowIfNeeded();
        int line = cursorLine();
        if (goalX < 0) goalX = widthOf(lines.get(line)[0], cursor);
        int target = line + dir;
        if (target < 0) {
            cursor = 0;
        } else if (target >= lines.size()) {
            cursor = value.length();
        } else {
            cursor = indexAtX(target, goalX);
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

    private int innerW() { return w - NumenStyle.FIELD_PAD * 2 - NumenStyle.SCROLLBAR_W - 1; }

    private int viewH() { return h - 4; }

    private int pitch() { return (measure == null ? 9 : measure.lineHeight()) + 2; }

    private int lineTop(int line) { return y + 2 + line * pitch() - scrollY; }

    private void reflowIfNeeded() {
        if (!dirty) return;
        dirty = false;
        List<int[]> out = new ArrayList<>();
        int len = value.length();
        int hardStart = 0;
        for (int i = 0; i <= len; i++) {
            if (i == len || value.charAt(i) == '\n') {
                wrapHardLine(out, hardStart, i);
                hardStart = i + 1;
            }
        }
        lines = out;
    }

    /** 一个硬行(无换行符)按宽度切成若干软行:空格优先断行,CJK 逐字断。 */
    private void wrapHardLine(List<int[]> out, int start, int end) {
        if (measure == null || innerW() <= 0 || start >= end) {
            out.add(new int[]{start, end});
            return;
        }
        int lineStart = start;
        int width = 0;
        int lastSpace = -1;
        int i = start;
        while (i < end) {
            char ch = value.charAt(i);
            int cw = measure.textWidth(String.valueOf(ch));
            if (width + cw > innerW() && i > lineStart) {
                int cut;
                if (ch == ' ') {
                    cut = i + 1;              // 压线的空格随行吞掉(行尾空格无需显示宽度)
                    i = cut;
                } else if (lastSpace > lineStart) {
                    cut = lastSpace + 1;      // 回退到最近空格后断,词不劈两半
                    i = cut;
                } else {
                    cut = i;                  // 整行无空格(CJK/长词):逐字断
                }
                out.add(new int[]{lineStart, cut});
                lineStart = cut;
                width = 0;
                lastSpace = -1;
                continue;                     // 回退过的字符从零重新累计宽度
            }
            if (ch == ' ') lastSpace = i;
            width += cw;
            i++;
        }
        out.add(new int[]{lineStart, end});
    }

    /** 光标所在的视觉行:软换行边界上的光标归下一行行首(通用编辑器行为)。 */
    private int cursorLine() {
        reflowIfNeeded();
        for (int i = 0; i < lines.size(); i++) {
            int[] span = lines.get(i);
            if (cursor < span[1]) return i;
            if (cursor == span[1] && (i == lines.size() - 1 || endsHard(i))) return i;
        }
        return lines.size() - 1;
    }

    /** 行尾是硬换行(其后是 \n)? */
    private boolean endsHard(int line) {
        int end = lines.get(line)[1];
        return end < value.length() && value.charAt(end) == '\n';
    }

    private boolean endsSoft(int line) {
        return line < lines.size() - 1 && !endsHard(line);
    }

    private int widthOf(int from, int to) {
        if (measure == null || from >= to) return 0;
        return measure.textWidth(value.substring(from, to));
    }

    /** 屏幕坐标 → 文本下标(行外点击夹到最近行/行首行尾)。 */
    private int indexAt(double mx, double my) {
        reflowIfNeeded();
        int line = Math.clamp((int) Math.floor((my - y - 2 + scrollY) / (double) pitch()),
                0, lines.size() - 1);
        return indexAtX(line, (int) (mx - x - NumenStyle.FIELD_PAD));
    }

    /** 行内横坐标(px,相对文本左缘)→ 下标:落在字符前半归左,后半归右。 */
    private int indexAtX(int line, int px) {
        int[] span = lines.get(line);
        if (measure == null) return span[1];
        int width = 0;
        for (int i = span[0]; i < span[1]; i++) {
            int cw = measure.textWidth(String.valueOf(value.charAt(i)));
            if (px < width + cw / 2) return i;
            width += cw;
        }
        return span[1];
    }

    private void clampScroll() {
        int contentH = lines.size() * pitch();
        scrollY = Math.clamp(scrollY, 0, Math.max(0, contentH - viewH()));
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
