package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.KeyCodes;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;

import java.util.function.Consumer;

/**
 * 单行文本输入。支持:光标移动/删改、Home/End、Ctrl+V 粘贴(API key 场景
 * 的刚需)、Ctrl+C 复制全文、掩码模式(密钥显示为 •)、占位符、水平滚动
 * (光标始终可见)。选区一期不做——设置场景里粘贴覆盖 > 局部选择。
 */
public final class TextField extends Widget {

    private final StringBuilder value = new StringBuilder();
    private final Consumer<String> onChange;
    private String placeholder = "";
    private boolean masked;
    private boolean numericOnly;
    private int cursor;
    /** 内联校验错误:字段红边 + 标签行右侧红字,驻留到用户开始修改。 */
    private String error;
    /** 可选:本字段的标签。错误文案与标签同处一行,长标签必撞——有错误时标签让位
     *  (一行只说一件事,而且此刻错误比标签重要)。 */
    private Label labelWidget;
    /** 视窗左缘对应的字符下标(水平滚动)。 */
    private int viewStart;
    /** 首词高亮的引导字符;0 = 不高亮。见 {@link #leadingToken}。 */
    private char tokenMarker;
    private int tokenColor;

    /**
     * 宿主提供的真编辑器;为空表示这个框自己管编辑(纯内存,无输入法)。
     * 绑上之后本控件<b>只画不编</b>——理由见 {@link TextInput}。
     */
    private TextInput host;

    public TextField(String initial, Consumer<String> onChange) {
        if (initial != null) value.append(initial);
        this.cursor = value.length();
        this.onChange = onChange;
    }

    public TextField placeholder(String text) {
        this.placeholder = text == null ? "" : text;
        return this;
    }

    public TextField masked(boolean masked) {
        this.masked = masked;
        return this;
    }

    /** 认领标签:出错时自动收起它,免得两串文字在同一行叠着。 */
    public TextField withLabel(Label label) {
        this.labelWidget = label;
        return this;
    }

    /**
     * 首词高亮:文本以 {@code marker} 开头时,第一个词(到空白为止)换个颜色画。
     *
     * <p>斜杠命令用它把 {@code /名字} 和后面的参数分开——一眼看出自己打的是命令还是
     * 一句话。掩码模式下不生效:那种场景里内容本来就不该被看出结构。
     */
    public TextField leadingToken(char marker, int argb) {
        this.tokenMarker = marker;
        this.tokenColor = argb;
        return this;
    }

    /**
     * 把编辑权交给宿主的真控件。绑上之后 {@link #charTyped}/{@link #keyPressed}
     * 一律不接,让事件落到宿主控件上——那是输入法认得出的那一个。
     */
    public TextField boundTo(TextInput input) {
        this.host = input;
        if (input != null) input.setText(value.toString());
        return this;
    }

    public String value() { return host != null ? host.text() : value.toString(); }

    public void setValue(String v) {
        if (host != null) {
            host.setText(v == null ? "" : v);
            return;
        }
        value.setLength(0);
        if (v != null) value.append(v);
        cursor = Math.min(cursor, value.length());
        viewStart = Math.min(viewStart, cursor);
    }

    public int cursor() { return host != null ? host.cursor() : cursor; }

    /** 光标移到末尾。补全之后要接着往下打,光标留在原处会插在半截。 */
    public void cursorToEnd() {
        if (host != null) {
            host.setText(host.text());   // 宿主控件的 setText 自带"光标去末尾"
            return;
        }
        cursor = value.length();
        viewStart = Math.min(viewStart, cursor);
    }

    /** 标记校验错误(内联展示);用户一开始输入即自动清除——错误跟着修复走。 */
    public void setError(String message) {
        this.error = message == null || message.isBlank() ? null : message;
    }

    public void clearError() { this.error = null; }

    public boolean hasError() { return error != null; }

    @Override
    public boolean focusable() { return true; }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        // 统一卡壳:圆角描边+内衬底;聚焦/错误只换描边色(STT 参考样式定标)。
        int border = error != null ? c.danger() : isFocused() ? c.accent() : c.inputBorder();
        NumenStyle.fieldCard(s, x, y, w, h, c.inputBg(), border);
        if (labelWidget != null) labelWidget.setVisible(error == null);   // 出错时标签让位
        if (error != null) {
            // 错误文案画在标签行(字段正上方)——错误出现在错误发生的地方;
            // 标签已收起,整行都归它,不必再让出三分之一。
            String msg = clipToWidth(s, error, w);
            s.drawText(msg, x + w - s.textWidth(msg), y - 10, c.danger(), false);
        }

        int pad = NumenStyle.FIELD_PAD;
        int innerW = w - pad * 2;

        if (host != null) {
            // 焦点是单向的:NumenUI 这边说了算(它先吃到点击),宿主控件跟着走。
            // 反过来双向同步的话,两份焦点状态迟早对不上。
            host.setFocused(isFocused());
            // 文字画在哪,真控件就摆在哪——输入法候选框跟着它的插入符定位。
            host.moveTo(x + pad, textY(s), innerW, s.lineHeight());
        }

        String raw = value();
        int caret = Math.min(cursor(), raw.length());
        String display = masked ? "•".repeat(raw.length()) : raw;

        if (display.isEmpty() && !isFocused()) {
            s.drawText(placeholder, x + pad, textY(s), c.textMuted(), false);
            return;
        }

        ensureCursorVisible(s, display, innerW, caret);
        String visible = clipToWidth(s, display.substring(viewStart), innerW);
        drawVisible(s, visible, x + pad, textY(s), c.textPrimary());

        if (isFocused() && (nowMs / 500) % 2 == 0) {   // 光标 1Hz 闪烁
            int cx = x + pad + s.textWidth(display.substring(viewStart, caret));
            s.fillRect(cx, y + 3, 1, h - 6, c.textPrimary());
        }
    }

    /** 画可见的那一段。开了首词高亮就拆成两笔,否则一笔画完。 */
    private void drawVisible(IDrawSurface s, String visible, int tx, int ty, int normal) {
        int end = tokenEnd();
        // end 是整串里的下标,visible 是从 viewStart 开始的那截 —— 换算到同一坐标系。
        int cut = Math.max(0, Math.min(end - viewStart, visible.length()));
        if (cut <= 0) {
            s.drawText(visible, tx, ty, normal, false);
            return;
        }
        String token = visible.substring(0, cut);
        s.drawText(token, tx, ty, tokenColor, false);
        if (cut < visible.length()) {
            s.drawText(visible.substring(cut), tx + s.textWidth(token), ty, normal, false);
        }
    }

    /** 首词在整串里的结束下标(不含);没开高亮或不是首词开头则 0。 */
    private int tokenEnd() {
        // 读 value() 而不是内部的 value:绑了宿主之后文本住在那边,读内部的会得到空串,
        // 斜杠命令的高亮会整个失效。
        String value = value();
        if (tokenMarker == 0 || masked || value.length() == 0
                || value.charAt(0) != tokenMarker) {
            return 0;
        }
        for (int i = 1; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return value.length();
    }

    private int textY(IDrawSurface s) {
        return y + (h - s.lineHeight()) / 2 + 1;
    }

    /**
     * 滚动视窗使光标可见:光标出左缘则左移视窗,出右缘则右移。
     *
     * <p><b>从光标往左退,不是从视窗起点往右挪。</b>两者结果一样,代价差一个量级:
     * 往右挪的话,每挪一格都要量 {@code viewStart..cursor} 整段,而那一段一开始就是
     * 整个值——粘进一条 600 字符的密钥(MiniMax 的是 JWT),每帧要量三十多万个字符,
     * 界面当场卡死,表现就是"太长了填不进去"。往左退只扫看得见的那几十个字符,
     * 与值多长无关。
     */
    private void ensureCursorVisible(IDrawSurface s, String display, int innerW, int caret) {
        viewStart = Math.min(viewStart, Math.max(0, display.length()));
        if (caret < viewStart) {
            viewStart = caret;
            return;
        }
        int start = caret;
        while (start > 0 && s.textWidth(display.substring(start - 1, caret)) <= innerW) {
            start--;
        }
        if (viewStart < start) viewStart = start;
    }

    private static String clipToWidth(IDrawSurface s, String text, int maxW) {
        int end = 0;
        while (end < text.length() && s.textWidth(text.substring(0, end + 1)) <= maxW) end++;
        return text.substring(0, end);
    }

    /**
     * 只收数字。
     *
     * <p>在<b>输入这一刻</b>挡住,而不是事后 parse 兜底——端口那种字段,让字母进得来就意味着
     * 保存时要多一条错误提示、还得想清楚那半截值算什么。挡在源头就没有这些问题。
     */
    public TextField numeric() {
        this.numericOnly = true;
        return this;
    }

    /** 当前值按整数读;空或读不动时返回 {@code fallback}。 */
    public int intValue(int fallback) {
        try {
            return Integer.parseInt(value.toString().strip());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    @Override
    public boolean charTyped(char ch) {
        // 绑了宿主就一个字符都不接:NumenScreen 是"NumenUI 先跑,处理了就 return true",
        // 这里一旦返回 true,super.charTyped 就轮不到,字符落不到那个真控件上,
        // 输入法辅助模组的 mixin 也就永远不触发。放行才是接上输入法的前提。
        if (host != null) return false;
        if (ch < ' ') return false;
        if (numericOnly && (ch < '0' || ch > '9')) return false;
        value.insert(cursor, ch);
        cursor++;
        fireChange();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int modifiers) {
        if (host != null) return false;   // 同 charTyped:让键落到宿主控件上
        if (KeyCodes.ctrl(modifiers)) {
            if (keyCode == KeyCodes.KEY_V) {
                String paste = root == null ? "" : root.clipboard();
                if (paste != null && !paste.isEmpty()) {
                    // 数字字段的粘贴也要过同一道筛子,否则 Ctrl+V 绕开了 charTyped 那关
                    String clean = numericOnly
                            ? paste.replaceAll("[^0-9]", "")
                            : paste.replaceAll("[\\r\\n]", "");
                    value.insert(cursor, clean);
                    cursor += clean.length();
                    fireChange();
                }
                return true;
            }
            if (keyCode == KeyCodes.KEY_C) {
                if (root != null) root.copyToClipboard(value.toString());
                return true;
            }
            if (keyCode == KeyCodes.KEY_A) {
                cursor = value.length();   // 无选区:Ctrl+A 语义退化为跳到末尾
                return true;
            }
        }
        switch (keyCode) {
            case KeyCodes.BACKSPACE -> {
                if (cursor > 0) {
                    value.deleteCharAt(--cursor);
                    fireChange();
                }
                return true;
            }
            case KeyCodes.DELETE -> {
                if (cursor < value.length()) {
                    value.deleteCharAt(cursor);
                    fireChange();
                }
                return true;
            }
            case KeyCodes.LEFT -> {
                cursor = Math.max(0, cursor - 1);
                return true;
            }
            case KeyCodes.RIGHT -> {
                cursor = Math.min(value.length(), cursor + 1);
                return true;
            }
            case KeyCodes.HOME -> {
                cursor = 0;
                return true;
            }
            case KeyCodes.END -> {
                cursor = value.length();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void fireChange() {
        error = null;   // 用户开始修改即撤下错误标记
        if (onChange != null) onChange.accept(value.toString());
    }
}
