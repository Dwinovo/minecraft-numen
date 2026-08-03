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
    private int cursor;
    /** 内联校验错误:字段红边 + 标签行右侧红字,驻留到用户开始修改。 */
    private String error;
    /** 视窗左缘对应的字符下标(水平滚动)。 */
    private int viewStart;

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

    public String value() { return value.toString(); }

    public void setValue(String v) {
        value.setLength(0);
        if (v != null) value.append(v);
        cursor = Math.min(cursor, value.length());
        viewStart = Math.min(viewStart, cursor);
    }

    public int cursor() { return cursor; }

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
        if (error != null) {
            // 错误文案画在标签行右侧(字段正上方)——错误出现在错误发生的地方。
            String msg = clipToWidth(s, error, w * 2 / 3);
            s.drawText(msg, x + w - s.textWidth(msg), y - 10, c.danger(), false);
        }

        int pad = NumenStyle.FIELD_PAD;
        int innerW = w - pad * 2;
        String display = masked ? "•".repeat(value.length()) : value.toString();

        if (display.isEmpty() && !isFocused()) {
            s.drawText(placeholder, x + pad, textY(s), c.textMuted(), false);
            return;
        }

        ensureCursorVisible(s, display, innerW);
        String visible = clipToWidth(s, display.substring(viewStart), innerW);
        s.drawText(visible, x + pad, textY(s), c.textPrimary(), false);

        if (isFocused() && (nowMs / 500) % 2 == 0) {   // 光标 1Hz 闪烁
            int cx = x + pad + s.textWidth(display.substring(viewStart, cursor));
            s.fillRect(cx, y + 3, 1, h - 6, c.textPrimary());
        }
    }

    private int textY(IDrawSurface s) {
        return y + (h - s.lineHeight()) / 2 + 1;
    }

    /** 滚动视窗使光标可见:光标出左缘则左移视窗,出右缘则右移。 */
    private void ensureCursorVisible(IDrawSurface s, String display, int innerW) {
        viewStart = Math.min(viewStart, Math.max(0, display.length()));
        if (cursor < viewStart) viewStart = cursor;
        while (viewStart < cursor
                && s.textWidth(display.substring(viewStart, cursor)) > innerW) {
            viewStart++;
        }
    }

    private static String clipToWidth(IDrawSurface s, String text, int maxW) {
        int end = 0;
        while (end < text.length() && s.textWidth(text.substring(0, end + 1)) <= maxW) end++;
        return text.substring(0, end);
    }

    @Override
    public boolean charTyped(char ch) {
        if (ch < ' ') return false;
        value.insert(cursor, ch);
        cursor++;
        fireChange();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int modifiers) {
        if (KeyCodes.ctrl(modifiers)) {
            if (keyCode == KeyCodes.KEY_V) {
                String paste = root == null ? "" : root.clipboard();
                if (paste != null && !paste.isEmpty()) {
                    String clean = paste.replaceAll("[\\r\\n]", "");
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
