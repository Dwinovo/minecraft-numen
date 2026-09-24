package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.KeyCodes;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.WrappedText;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 单行文本输入。支持:光标移动/删改、Home/End、Ctrl+V 粘贴(API key 场景
 * 的刚需)、Ctrl+C 复制全文、掩码模式(密钥显示为 •)、占位符、水平滚动
 * (光标始终可见)。选区一期不做——设置场景里粘贴覆盖 > 局部选择。
 *
 * <p>{@link #maxLines} 大于 1 时是聊天输入框那样的多行(Telegram 的输入框):按宽度折行,Shift+回车换行
 * (回车本身不接,留给宿主发送),↑↓ 跨行,点哪光标到哪;最多露几行由宿主照 {@link #visibleLines} 往上长,
 * 再多就在框里滚。折行几何与多行输入框同一份({@link WrappedText});掩码只在单行里有。
 */
public final class TextField extends Widget {

    private final StringBuilder value = new StringBuilder();
    private final Consumer<String> onChange;
    private String placeholder = "";
    private boolean masked;
    /** 只画一道下划线、不画卡壳。见 {@link #underlined}。 */
    private boolean underlined;
    /** 什么框都不画,底色由宿主给。见 {@link #bare}。 */
    private boolean bare;
    private boolean numericOnly;
    private int cursor;
    /** 内联校验错误:字段红边 + 标签行右侧红字,驻留到用户开始修改。 */
    private String error;
    /** 可选:本字段的标签。错误文案与标签同处一行,长标签必撞——有错误时标签让位
     *  (一行只说一件事,而且此刻错误比标签重要)。 */
    private Label labelWidget;
    /** 视窗左缘对应的字符下标(水平滚动)。 */
    private int viewStart;
    /** 哪些段换色画,由宿主按内容算;null = 全按正文色。见 {@link #highlight}。 */
    private Function<String, List<Span>> highlighter;

    /** 最多露几行;1 = 单行(横向滚动)。见 {@link #maxLines}。 */
    private int maxLines = 1;
    /** 多行:最近一次画的画布(量宽、行高都照它),折行缓存(按字、宽、量没量过认),露出的第一行。 */
    private IDrawSurface measure;
    private WrappedText wrapped;
    private String wrappedText;
    private int wrappedWidth = -1;
    private boolean wrappedMeasured;
    private int scrollLine;
    /** 多行:上次画时的光标与字——变了才把光标那行滚进来,滚轮翻开的不被每帧拽回去。 */
    private int shownCaret = -1;
    private String shownText;
    /** 多行:↑↓ 连按沿同一个横坐标走(穿过短行不丢列);-1 = 没在上下移动。 */
    private int goalX = -1;
    /** 多行:最底下那行字离框底多远,光标竖线上下各多出一像素。 */
    private static final int ROW_INSET = 4;

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

    /**
     * 嵌在一行里的输入框:只画底边一道线(聚焦、出错照样换色),不画框——它是那一行的一部分,
     * 不是另一个框。
     */
    public TextField underlined(boolean underlined) {
        this.underlined = underlined;
        return this;
    }

    /**
     * 嵌在宿主画好的一整条底色里的输入框(Telegram 的输入区:输入框和旁边的键同一条底,没有框线):
     * 不画框也不画底,聚焦不换色——聚焦由光标表明。
     */
    public TextField bare(boolean bare) {
        this.bare = bare;
        return this;
    }

    /**
     * 最多露几行。大于 1 就是多行:折行、Shift+回车换行、↑↓ 跨行;宿主照 {@link #visibleLines} 把框往上长,
     * 超过这么多行在框里滚。
     */
    public TextField maxLines(int lines) {
        this.maxLines = Math.max(1, lines);
        return this;
    }

    /** 多行:行与行的间距。 */
    public int linePitch() {
        return (measure == null ? 9 : measure.lineHeight()) + 1;
    }

    /** 多行:此刻该露几行(1..{@link #maxLines}),宿主照它定框高。量过一次之前按换行符算。 */
    public int visibleLines() {
        return Math.min(maxLines, wrap().lineCount());
    }

    /** 认领标签:出错时自动收起它,免得两串文字在同一行叠着。 */
    public TextField withLabel(Label label) {
        this.labelWidget = label;
        return this;
    }

    /** 一段要换色画的文字:整串里的 {@code [start, end)} 与颜色。互不重叠、按位置升序。 */
    public record Span(int start, int end, int argb) {}

    /**
     * 局部换色:宿主拿整串文本算出哪些段换什么颜色——斜杠命令的首词、{@code @} 到的名字。
     * 内容里什么算一段是宿主的事,框只管照着画。掩码模式下不生效:那种场景里内容本来就不该被看出结构。
     */
    public TextField highlight(Function<String, List<Span>> highlighter) {
        this.highlighter = highlighter;
        return this;
    }

    /**
     * 加进 {@link UiRoot} 时自动换上宿主的真编辑器。根上没接工厂就保持纯内存模式。
     *
     * <p>绑上之后本控件<b>只画不编</b>:{@link #charTyped}/{@link #keyPressed} 一律不接,
     * 让事件落到宿主控件上——那是输入法认得出的那一个。
     */
    void attachHost(UiRoot r) {
        var factory = r.inputFactory();
        if (factory == null || host != null) return;
        TextInput in = factory.apply(value.toString(), this::fireChangeWith);
        if (in == null) return;   // 工厂可以拒绝(不在受支持的屏幕里),那就保持纯内存
        host = in;
        if (numericOnly) host.setNumericOnly(true);   // 先 numeric() 后 add 的调用点
    }

    /** 宿主控件改了文本:原样转给本控件的 onChange。 */
    private void fireChangeWith(String v) {
        error = null;   // 和 fireChange 一样:用户开始修改即撤下错误标记,宿主模式也不例外
        if (onChange != null) onChange.accept(v);
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

    /** 把光标放到 {@code pos}。宿主想把光标留在改动处而不是末尾时用(如整体删掉一个 @名字)。 */
    public void setCursor(int pos) {
        if (host != null) {
            host.setCursor(pos);
            return;
        }
        cursor = Math.max(0, Math.min(pos, value.length()));
        viewStart = Math.min(viewStart, cursor);
    }

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
        // 统一的框:描边+内衬底;聚焦/错误只换描边色(STT 参考样式定标)。
        int border = error != null ? c.danger() : isFocused() ? c.accent() : c.inputBorder();
        if (underlined) {
            s.fillRect(x, y + h - 1, w, 1, border);
        } else if (!bare) {
            NumenStyle.box(s, x, y, w, h, c.inputBg(), border);
        }
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
        }
        if (maxLines > 1) {
            renderLines(s, c, nowMs, pad);
            return;
        }
        if (host != null) {
            // 文字画在哪,真控件就摆在哪——输入法候选框跟着它的插入符定位。
            host.moveTo(x + pad, textY(s), innerW, s.lineHeight());
        }

        String raw = value();
        int caret = Math.min(cursor(), raw.length());
        String display = masked ? "•".repeat(raw.length()) : raw;

        if (display.isEmpty()) {
            // 空着就给占位,聚焦着也给——打第一个字才让开(Telegram 的"写消息…"一直在)
            s.drawText(placeholder, x + pad, textY(s), c.textMuted(), false);
            if (!isFocused()) return;
        }

        ensureCursorVisible(s, display, innerW, caret);
        String visible = clipToWidth(s, display.substring(viewStart), innerW);
        // 读 value() 而不是内部的 value:绑了宿主之后文本住在那边,读内部的会得到空串,高亮会整个失效。
        List<Span> spans = highlighter == null || masked ? List.of() : highlighter.apply(raw);
        drawVisible(s, spans, visible, viewStart, x + pad, textY(s), c.textPrimary());

        if (isFocused() && (nowMs / 500) % 2 == 0) {   // 光标 1Hz 闪烁
            int cx = x + pad + s.textWidth(display.substring(viewStart, caret));
            s.fillRect(cx, y + 3, 1, h - 6, c.textPrimary());
        }
    }

    /**
     * 多行:行从框底往上排(框往上长时最底下那行不动,上面的行被裁着露出来),光标那行始终露着,
     * 超过 {@link #maxLines} 行时右缘一根滑块。
     */
    private void renderLines(IDrawSurface s, NumenTheme.Colors c, long nowMs, int pad) {
        measure = s;
        String raw = value();
        int caret = Math.min(cursor(), raw.length());
        WrappedText lay = wrap();
        int pitch = linePitch();
        int n = lay.lineCount();
        int shown = Math.min(maxLines, n);
        int caretLine = lay.lineOf(caret);
        if (caret != shownCaret || !raw.equals(shownText)) {
            if (caretLine < scrollLine) scrollLine = caretLine;
            if (caretLine >= scrollLine + shown) scrollLine = caretLine - shown + 1;
            shownCaret = caret;
            shownText = raw;
        }
        scrollLine = Math.max(0, Math.min(scrollLine, n - shown));
        int caretY = rowTop(caretLine, shown, pitch);
        if (host != null) {
            // 文字画在哪,真控件就摆在哪——输入法候选框跟着它的插入符定位(摆在光标那一行)。
            host.moveTo(x + pad, caretY + 1, wrapWidth(), s.lineHeight());
        }
        if (raw.isEmpty()) {
            s.drawText(placeholder, x + pad, rowTop(0, shown, pitch) + 1, c.textMuted(), false);
            if (!isFocused()) return;
        }
        s.pushScissor(x, y, w, h);
        List<Span> spans = highlighter == null ? List.of() : highlighter.apply(raw);
        for (int r = scrollLine; r < scrollLine + shown; r++) {
            drawVisible(s, spans, lay.line(r), lay.start(r), x + pad, rowTop(r, shown, pitch) + 1, c.textPrimary());
        }
        if (isFocused() && (nowMs / 500) % 2 == 0) {   // 光标 1Hz 闪烁
            s.fillRect(x + pad + lay.xOf(caret), caretY - 1, 1, pitch + 2, c.textPrimary());
        }
        s.popScissor();
        if (n > shown) {
            int thumbH = Math.max(4, h * shown / n);
            int thumbY = y + (h - thumbH) * scrollLine / (n - shown);
            s.fillRect(x + w - NumenStyle.SCROLLBAR_W, thumbY, NumenStyle.SCROLLBAR_W, thumbH, c.divider());
        }
    }

    /** 多行:第 {@code row} 行的顶边(露出的最后一行贴着框底)。 */
    private int rowTop(int row, int shown, int pitch) {
        return y + h - ROW_INSET - (scrollLine + shown - row) * pitch;
    }

    /** 多行:一行最多多宽(右缘给滑块留一条,满不满都留,免得多出一行时整段重折)。 */
    private int wrapWidth() {
        return w - NumenStyle.FIELD_PAD * 2 - NumenStyle.SCROLLBAR_W - 1;
    }

    /** 多行:此刻的字按此刻的宽折好的几何;字、宽、量没量过都没变就用上次的。 */
    private WrappedText wrap() {
        String text = value();
        int width = wrapWidth();
        boolean measured = measure != null;
        if (wrapped == null || !text.equals(wrappedText) || width != wrappedWidth || measured != wrappedMeasured) {
            wrapped = WrappedText.of(text, width, measured ? t -> measure.textWidth(t) : null);
            wrappedText = text;
            wrappedWidth = width;
            wrappedMeasured = measured;
        }
        return wrapped;
    }

    /** 多行:↑↓ 到上一行、下一行同一个横坐标处;到顶、到底就去全文头尾。 */
    private void verticalMove(int dir) {
        WrappedText lay = wrap();
        String raw = value();
        int caret = Math.min(cursor(), raw.length());
        if (goalX < 0) goalX = lay.xOf(caret);
        int target = lay.lineOf(caret) + dir;
        setCursor(target < 0 ? 0 : target >= lay.lineCount() ? raw.length() : lay.indexAtX(target, goalX));
    }

    /** 多行:在光标处换行。宿主的真编辑器不收换行符的输入,所以整段写回去再把光标放到新行行首。 */
    private void insertNewline() {
        String raw = value();
        int at = Math.min(cursor(), raw.length());
        if (host != null) {
            host.setText(raw.substring(0, at) + "\n" + raw.substring(at));
            host.setCursor(at + 1);
            return;
        }
        value.insert(at, '\n');
        cursor = at + 1;
        fireChange();
    }

    /** 多行:点哪光标到哪。 */
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (maxLines <= 1 || button != 0 || !contains(mx, my)) return false;
        WrappedText lay = wrap();
        int shown = Math.min(maxLines, lay.lineCount());
        int fromBottom = (int) Math.floor((y + h - ROW_INSET - my) / linePitch());
        int row = Math.max(0, Math.min(lay.lineCount() - 1, scrollLine + shown - 1 - fromBottom));
        setCursor(lay.indexAtX(row, (int) (mx - x - NumenStyle.FIELD_PAD)));
        goalX = -1;
        return true;
    }

    /** 多行:写满 {@link #maxLines} 行以后滚轮在框里翻。 */
    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (maxLines <= 1) return false;
        int n = wrap().lineCount();
        if (n <= maxLines) return false;
        scrollLine = Math.max(0, Math.min(n - maxLines, scrollLine - (int) Math.signum(delta)));
        return true;
    }

    /**
     * 画一段:换色的段与正文段交替各画一笔,没有换色的段就一笔画完。
     *
     * @param offset {@code visible} 的第一个字在整串里是第几个(段的下标是整串的)
     */
    private void drawVisible(IDrawSurface s, List<Span> spans, String visible, int offset, int tx, int ty,
                             int normal) {
        int x = tx;
        int at = 0;   // visible 里画到哪了;段的下标是整串的,减 offset 换算到同一坐标系
        for (Span sp : spans) {
            int a = Math.max(at, sp.start() - offset);
            int b = Math.min(visible.length(), sp.end() - offset);
            if (b <= a) continue;
            if (a > at) {
                String plain = visible.substring(at, a);
                s.drawText(plain, x, ty, normal, false);
                x += s.textWidth(plain);
            }
            String run = visible.substring(a, b);
            s.drawText(run, x, ty, sp.argb(), false);
            x += s.textWidth(run);
            at = b;
        }
        if (at < visible.length()) {
            s.drawText(visible.substring(at), x, ty, normal, false);
        }
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
        if (host != null) host.setNumericOnly(true);   // 绑定后过滤归宿主,见 TextInput
        return this;
    }

    /** 当前值按整数读;空或读不动时返回 {@code fallback}。 */
    public int intValue(int fallback) {
        // 读 value() 而不是内部的 value:绑了宿主之后文本住在那边(同 tokenEnd)。
        try {
            return Integer.parseInt(value().strip());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    @Override
    public boolean charTyped(char ch) {
        goalX = -1;
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
        // 多行的 ↑↓ 与 Shift+回车宿主的真编辑器不管(它是单行的),绑没绑宿主都在这里接;回车本身不接,留给宿主发送
        if (maxLines > 1) {
            if ((keyCode == KeyCodes.UP || keyCode == KeyCodes.DOWN) && !KeyCodes.ctrl(modifiers)) {
                verticalMove(keyCode == KeyCodes.UP ? -1 : 1);
                return true;
            }
            goalX = -1;
            if (keyCode == KeyCodes.ENTER && KeyCodes.shift(modifiers)) {
                insertNewline();
                return true;
            }
        }
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
