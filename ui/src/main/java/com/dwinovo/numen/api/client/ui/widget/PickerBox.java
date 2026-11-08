package com.dwinovo.numen.api.client.ui.widget;

import com.dwinovo.numen.api.client.ui.Animation;
import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.KeyCodes;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.TextClip;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * 选择框:长列表(厂家、型号)的选法。窗口中央一张卡、后面压暗,上面标题加一句数量提示,
 * 一个搜索框(打开即聚焦,输入即过滤),中间分节的列表,底下一行按键提示——Telegram 选语言、选国家的那种框。
 * 键盘:↑↓ 选、回车定、Esc 关;点卡外关;滚轮滚。
 *
 * <p>它不知道列的是什么:调用方以“节 + 行”({@link Section}、{@link Item})经 {@link Source} 给进来,
 * 搜什么、怎么排、怎么匹配都是调用方的事;控件只管几何、选中、滚动和画。搜索框是宿主的真输入框
 * (经 {@link UiRoot} 上的工厂,与表单里其它输入框同一条路),所以输入法认得出来。
 *
 * <p>走 {@link UiRoot.Overlay} 通道,外壳与出没是 {@link DialogBox}(暗幕和卡一起淡入淡出);
 * 卡的高随内容,内容变了(过滤)就缓缓长短,而不是跳。一个实例可以反复 {@link #open}:搜索框只建一次
 * (每建一个,宿主屏幕就多挂一个真输入框),随宿主面板重建而换新。
 */
public final class PickerBox implements UiRoot.Overlay {

    /**
     * 一行。
     *
     * @param id       调用方认的标识;手填行的 id 就是搜索词
     * @param title    主文字
     * @param detail   主文字下面一行次要小字(如型号 id、主机名),可空
     * @param cells    右侧各列的小字,与 {@link Table#columns} 逐列对应;没有列表头的框给空表
     * @param current  当前选中项(行首打勾)
     */
    public record Item(String id, String title, String detail, List<String> cells, boolean current) {
        public Item {
            detail = detail == null ? "" : detail;
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    /** 一节。{@code label} 为空 = 没有节标题;没有行的节不画。 */
    public record Section(String label, List<Item> items) {}

    /** 搜索词(已去首尾空白,空串 = 没搜)→ 要列的节。 */
    public interface Source {
        List<Section> sections(String query);
    }

    /**
     * 框上的字,全由调用方给(控件不带语言)。
     *
     * @param hint   标题旁边的一句提示(如“共 228 家”)
     * @param keys   底部按键提示,逐条排开
     * @param noMatch 搜不到时的一句话,参数是搜索词
     */
    public record Labels(String title, String hint, String placeholder, List<String> keys,
                         UnaryOperator<String> noMatch) {}

    /**
     * 手填行:搜索词不等于任何一项的 id 时,列表末尾多一节、一行“使用 xxx”,选了它得到 id = 搜索词的 {@link Item}。
     *
     * @param section 节标题
     * @param title   行的主文字,参数是搜索词
     * @param detail  行的次要小字
     */
    public record FreeEntry(String section, UnaryOperator<String> title, String detail) {}

    /**
     * 一列右侧的小字。
     *
     * @param header 列头
     * @param chars  列宽,按这么多个数字的宽度算(数字等宽,右对齐的列上下对得齐)
     * @param right  右对齐(数字列);否则左对齐,放不下的省略
     */
    public record Column(String header, int chars, boolean right) {}

    /**
     * 带列头的表格版式:搜索框下面、列表上面多一行不随列表滚的列头,条目右侧按列排小字,卡也宽一些。
     *
     * @param titleHeader 主文字那一列的列头
     * @param maxWidth    卡宽上限(GUI 像素)
     */
    public record Table(String titleHeader, List<Column> columns, int maxWidth) {}

    /** 卡的大小。 */
    public record Size(int w, int h) {}

    /** 卡宽、卡高的上限(GUI 像素),与占窗口宽、高的比例(百分)。 */
    private static final int MAX_W = DialogBox.WIDE_WIDTH + 32;
    private static final int MAX_H = 280;
    private static final int WIDTH_PCT = 88;
    private static final int HEIGHT_PCT = 86;

    /**
     * 卡多大:宽不超过窗口(和 {@code maxW}),高随内容、不超过 {@link #MAX_H} 与窗口的 86%——卡永远整块落在窗口里,
     * 标题、搜索、列头、页脚固定,放不下的只有列表,在卡里滚。纯算术,单测钉住。
     *
     * @param contentH 装下全部内容要的高
     */
    public static Size size(int dimW, int dimH, int contentH, int maxW) {
        return new Size(Math.min(maxW, dimW * WIDTH_PCT / 100),
                Math.min(Math.min(contentH, MAX_H), dimH * HEIGHT_PCT / 100));
    }

    /** 行两端文字到卡边的距离;打勾那一格的宽;搜索框到列表的间距。 */
    private static final int INSET = 10;
    private static final int CHECK_W = 12;
    private static final int SEARCH_GAP = 8;
    /** 内容高变了,卡长短到位的快慢(毫秒级时间常数,按帧间隔折算)。 */
    private static final float RESIZE_MS = 80f;

    /** 列表里的一行:节标题({@code item == null})或一个条目。 */
    private record Row(String label, Item item) {}

    private final DialogBox box = new DialogBox();
    private UiRoot root;
    private UiRoot inner;
    private TextField search;

    private Labels labels;
    private Table table;
    private Source source;
    private FreeEntry free;
    private Consumer<Item> onPick;
    private int dimX, dimY, dimW, dimH;

    private String query = "";
    private List<Row> rows = List.of();
    /** 键盘与指针共用的“当前行”(rows 的下标,总是条目行);-1 = 没有可选的。 */
    private int active = -1;
    private float activeT;
    private boolean reveal;
    /** 打开那一刻:当前项居中露出(之后键盘移动只求露出来就行)。 */
    private boolean centerOnReveal;
    private int scrollY;
    private int lastMouseX = Integer.MIN_VALUE, lastMouseY = Integer.MIN_VALUE;
    private long lastFrameMs = -1;
    private float shownH = -1;

    // 最近一次画时的几何,事件无画布也能判命中
    private int cardX, cardY, cardW, cardH;
    private int listTop, listH, itemH, sectionH;
    private int[] tops = new int[0];

    /**
     * 打开。{@code root} 是挂浮层的根,搜索框用它上面的输入框工厂与剪贴板;{@code dim*} 是暗幕覆盖区(通常是宿主窗口),
     * 卡居中其内。
     *
     * @param table 表格版式;null = 只有主文字和次要小字的普通列表
     * @param free  手填行;null = 不给手填
     */
    public void open(UiRoot root, int dimX, int dimY, int dimW, int dimH,
                     Labels labels, Table table, Source source, FreeEntry free, Consumer<Item> onPick) {
        this.root = root;
        this.dimX = dimX;
        this.dimY = dimY;
        this.dimW = dimW;
        this.dimH = dimH;
        this.labels = labels;
        this.table = table;
        this.source = source;
        this.free = free;
        this.onPick = onPick;
        if (inner == null) {
            inner = new UiRoot();
            inner.setInputFactory(root.inputFactory());
            inner.setClipboard(root::clipboard, root::copyToClipboard);
            search = inner.add(new TextField("", this::onQuery));
        }
        search.placeholder(labels.placeholder());
        search.setValue("");
        query = "";
        rebuild();
        active = firstCurrent();
        activeT = 0;
        reveal = true;
        centerOnReveal = true;
        scrollY = 0;
        shownH = -1;
        lastFrameMs = -1;
        lastMouseX = lastMouseY = Integer.MIN_VALUE;
        inner.requestFocus(search);
        box.show();
        root.openOverlay(this);
    }

    public boolean isOpen() {
        return box.shown();
    }

    // ---- 内容 ----

    private void onQuery(String text) {
        String q = text == null ? "" : text.strip();
        if (q.equals(query)) return;
        query = q;
        rebuild();
        active = firstItem();
        activeT = 0;
        reveal = true;
        scrollY = 0;
    }

    private void rebuild() {
        List<Row> out = new ArrayList<>();
        boolean exact = false;
        for (Section sec : source.sections(query)) {
            if (sec.items().isEmpty()) continue;
            if (!sec.label().isEmpty()) out.add(new Row(sec.label(), null));
            for (Item it : sec.items()) {
                out.add(new Row(null, it));
                exact |= it.id().equalsIgnoreCase(query);
            }
        }
        if (free != null && !query.isEmpty() && !exact) {
            out.add(new Row(free.section(), null));
            out.add(new Row(null, new Item(query, free.title().apply(query), free.detail(), List.of(), false)));
        }
        rows = out;
    }

    private int firstItem() {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).item() != null) return i;
        }
        return -1;
    }

    private int firstCurrent() {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).item() != null && rows.get(i).item().current()) return i;
        }
        return firstItem();
    }

    private void setActive(int index) {
        if (index == active) return;
        active = index;
        activeT = 0;
    }

    private void move(int dir) {
        int i = active;
        do {
            i += dir;
        } while (i >= 0 && i < rows.size() && rows.get(i).item() == null);
        if (i < 0 || i >= rows.size()) return;   // 到头就停,不绕圈
        setActive(i);
        reveal = true;
    }

    private void pick(int index) {
        Item it = rows.get(index).item();
        detach();
        onPick.accept(it);
    }

    private void detach() {
        close();
        if (root != null) root.closeOverlay(this);
    }

    private void close() {
        box.hide();
        inner.requestFocus(null);   // 真输入框跟着收焦点,不再接键
    }

    // ---- 画 ----

    @Override
    public void renderOverlay(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        box.advance(nowMs);
        draw(s, c, mouseX, mouseY, nowMs);
    }

    @Override
    public boolean renderLeaving(IDrawSurface s, NumenTheme.Colors c, long nowMs) {
        if (!box.advance(nowMs)) return false;
        draw(s, c, Integer.MIN_VALUE, Integer.MIN_VALUE, nowMs);   // 淡出中不接指针,也就不画悬停
        return true;
    }

    private void draw(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        long dt = lastFrameMs < 0 ? 1000 : nowMs - lastFrameMs;
        lastFrameMs = nowMs;
        int lh = s.lineHeight();
        itemH = lh * 2 + 4;
        sectionH = lh + 6;
        int headerH = table == null ? 0 : lh + 5;
        int fixedH = DialogBox.TITLE_H + NumenStyle.CONTROL_H + SEARCH_GAP + headerH + (lh + 9);

        tops = new int[rows.size() + 1];
        int contentH = 0;
        for (int i = 0; i < rows.size(); i++) {
            tops[i] = contentH;
            contentH += rows.get(i).item() == null ? sectionH : itemH;
        }
        tops[rows.size()] = contentH;
        int emptyH = lh * 2 + 8;
        int listContentH = rows.isEmpty() ? emptyH : contentH;

        Size want = size(dimW, dimH, fixedH + listContentH, table == null ? MAX_W : table.maxWidth());
        shownH = shownH < 0 ? want.h() : Animation.lerpTo(shownH, want.h(), Math.min(1f, dt / RESIZE_MS), 0.5f);
        cardW = want.w();
        cardH = Math.round(shownH);
        cardX = dimX + (dimW - cardW) / 2;
        cardY = dimY + (dimH - cardH) / 2;
        int headerTop = cardY + DialogBox.TITLE_H + NumenStyle.CONTROL_H + SEARCH_GAP;
        listTop = headerTop + headerH;
        int footerTop = cardY + cardH - (lh + 9);
        listH = Math.max(0, footerTop - listTop);
        box.paint(s, c, dimX, dimY, dimW, dimH, cardX, cardY, cardW, cardH);
        float a = box.card();
        // 淡到几乎看不见就不画字:MC 把不透明度低于 4/255 的字当成不透明来画
        if (a < 0.02f) return;
        NumenTheme.Colors fc = DialogBox.fade(c, a);

        int tx = cardX + INSET;
        int ty = cardY + DialogBox.TITLE_TOP;
        s.drawText(labels.title(), tx, ty, fc.textPrimary(), false);
        int hintX = tx + s.textWidth(labels.title()) + 8;
        s.drawText(TextClip.fit(s, labels.hint(), cardX + cardW - INSET - hintX), hintX, ty, fc.textSecondary(), false);

        search.setBounds(tx, cardY + DialogBox.TITLE_H, cardW - INSET * 2, NumenStyle.CONTROL_H);
        inner.renderContent(s, fc, mouseX, mouseY, nowMs);

        if (table != null) drawHeader(s, fc, tx, headerTop, lh);
        s.fillRect(cardX + 1, listTop - 1, cardW - 2, 1, fc.divider());
        s.fillRect(cardX + 1, footerTop, cardW - 2, 1, fc.divider());
        int kx = tx;
        for (String k : labels.keys()) {
            s.drawText(k, kx, footerTop + 5, fc.textSecondary(), false);
            kx += s.textWidth(k) + 12;
        }

        int maxScroll = Math.max(0, listContentH - listH);
        // 指针动了才让指针夺走当前行;键盘在动时指针停着不动,不能把它拽回去
        if ((mouseX != lastMouseX || mouseY != lastMouseY) && inList(mouseX, mouseY)) {
            int hit = rowAt(mouseY);
            if (hit >= 0) setActive(hit);
        }
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if (reveal && active >= 0) {
            if (centerOnReveal) scrollY = (tops[active] + tops[active + 1] - listH) / 2;
            else revealRow(active);
            reveal = false;
            centerOnReveal = false;
        }
        scrollY = Math.max(0, Math.min(maxScroll, scrollY));
        activeT = NumenStyle.hoverStep(activeT, true, dt);

        s.pushScissor(cardX + 1, listTop, cardW - 2, listH);
        if (rows.isEmpty()) {
            s.drawText(TextClip.fit(s, labels.noMatch().apply(query), cardW - INSET * 2), tx, listTop + 6,
                    fc.textSecondary(), false);
        }
        for (int i = 0; i < rows.size(); i++) {
            int ry = listTop + tops[i] - scrollY;
            int rh = tops[i + 1] - tops[i];
            if (ry + rh <= listTop || ry >= listTop + listH) continue;
            Row row = rows.get(i);
            if (row.item() == null) {
                s.drawText(row.label(), tx, ry + 4, fc.textSecondary(), false);
            } else {
                drawItem(s, fc, row.item(), i == active, tx, ry, lh);
            }
        }
        s.popScissor();

        if (maxScroll > 0) {
            int thumbH = Math.max(10, listH * listH / listContentH);
            int thumbY = listTop + (listH - thumbH) * scrollY / maxScroll;
            s.fillRect(cardX + cardW - 1 - NumenStyle.SCROLLBAR_W, thumbY, NumenStyle.SCROLLBAR_W, thumbH,
                    fc.divider());
        }
    }

    private void drawItem(IDrawSurface s, NumenTheme.Colors fc, Item it, boolean isActive, int tx, int ry, int lh) {
        if (isActive && activeT > 0.01f) {
            int overlay = ((int) (((fc.hover() >>> 24) & 0xFF) * activeT) << 24) | (fc.hover() & 0xFFFFFF);
            s.fillRect(cardX + 1, ry, cardW - 2, itemH, overlay);
        }
        if (it.current()) {
            s.drawText("✓", tx, ry + (itemH - lh) / 2 + 1, fc.accent(), false);
        }
        int textX = tx + CHECK_W;
        int room = columnsLeft(s) - textX;
        for (int i = 0; i < cellCount(it); i++) {
            Column col = table.columns().get(i);
            int cw = columnWidth(s, col);
            String cell = TextClip.fit(s, it.cells().get(i), cw);
            int cx = columnX(s, i) + (col.right() ? cw - s.textWidth(cell) : 0);
            s.drawText(cell, cx, ry + (itemH - lh) / 2 + 1, fc.textSecondary(), false);
        }
        s.drawText(TextClip.fit(s, it.title(), room), textX, ry + 2, fc.textPrimary(), false);
        if (!it.detail().isEmpty()) {
            // 当前行铺着悬停底,次要小字提一档对比,免得糊在底色里
            s.drawText(TextClip.fit(s, it.detail(), room), textX, ry + 2 + lh,
                    isActive ? fc.textSecondary() : fc.textMuted(), false);
        }
    }

    private int cellCount(Item it) {
        return table == null ? 0 : Math.min(it.cells().size(), table.columns().size());
    }

    private static final int COLUMN_GAP = 8;

    private int columnWidth(IDrawSurface s, Column col) {
        return s.textWidth("0".repeat(col.chars()));
    }

    /** 右侧各列从右往左排,最右一列贴着滚动条左边;这是第 {@code i} 列的左缘。 */
    private int columnX(IDrawSurface s, int i) {
        int x = cardX + cardW - INSET - NumenStyle.SCROLLBAR_W;
        for (int k = table.columns().size() - 1; k >= i; k--) {
            x -= columnWidth(s, table.columns().get(k)) + (k == table.columns().size() - 1 ? 0 : COLUMN_GAP);
        }
        return x;
    }

    /** 主文字能用到的右缘(最左一列的左边再留一道间距);没有列就是滚动条左边。 */
    private int columnsLeft(IDrawSurface s) {
        if (table == null || table.columns().isEmpty()) return cardX + cardW - INSET - NumenStyle.SCROLLBAR_W;
        return columnX(s, 0) - COLUMN_GAP;
    }

    private void drawHeader(IDrawSurface s, NumenTheme.Colors fc, int tx, int top, int lh) {
        int ty = top + 1;
        s.drawText(table.titleHeader(), tx + CHECK_W, ty, fc.textSecondary(), false);
        for (int i = 0; i < table.columns().size(); i++) {
            Column col = table.columns().get(i);
            int cw = columnWidth(s, col);
            String h = TextClip.fit(s, col.header(), cw);
            s.drawText(h, columnX(s, i) + (col.right() ? cw - s.textWidth(h) : 0), ty, fc.textSecondary(), false);
        }
    }

    /** 让第 {@code index} 行整个落进视口;它是节里的第一行就连节标题一起带进来。 */
    private void revealRow(int index) {
        int top = tops[index];
        if (index > 0 && rows.get(index - 1).item() == null) top = tops[index - 1];
        int bottom = tops[index + 1];
        if (top < scrollY) scrollY = top;
        else if (bottom > scrollY + listH) scrollY = bottom - listH;
    }

    private boolean inCard(double mx, double my) {
        return mx >= cardX && mx < cardX + cardW && my >= cardY && my < cardY + cardH;
    }

    private boolean inList(double mx, double my) {
        return mx >= cardX && mx < cardX + cardW && my >= listTop && my < listTop + listH;
    }

    /** 纵坐标落在哪个条目行上;节标题、空白给 -1。 */
    private int rowAt(double my) {
        if (tops.length != rows.size() + 1) return -1;
        int y = (int) my - listTop + scrollY;
        for (int i = 0; i < rows.size(); i++) {
            if (y >= tops[i] && y < tops[i + 1]) return rows.get(i).item() != null ? i : -1;
        }
        return -1;
    }

    // ---- 事件 ----

    @Override
    public boolean overlayClicked(double mx, double my, int button) {
        if (!inCard(mx, my)) return false;   // 卡外:root 关掉它
        if (search.contains(mx, my)) {
            inner.mouseClicked(mx, my, button);
        } else if (button == 0 && inList(mx, my)) {
            int hit = rowAt(my);
            if (hit >= 0) pick(hit);
        }
        return true;
    }

    @Override
    public boolean overlayScrolled(double mx, double my, double delta) {
        scrollY = Math.max(0, scrollY - (int) (delta * itemH));   // 上界在下一帧画时夹
        return true;   // 模态:滚轮不漏给后面的表单
    }

    @Override
    public boolean overlayKey(int keyCode, int modifiers) {
        switch (keyCode) {
            case KeyCodes.UP -> move(-1);
            case KeyCodes.DOWN -> move(1);
            case KeyCodes.ENTER -> {
                if (active >= 0) pick(active);
            }
            case KeyCodes.TAB -> { }   // 屏幕的 Tab 会把焦点从搜索框上带走
            default -> {
                return inner.keyPressed(keyCode, modifiers);
            }
        }
        return true;
    }

    @Override
    public boolean overlayChar(char ch) {
        return inner.charTyped(ch);
    }

    @Override
    public void closeOverlay() {
        // root 侧关闭(Esc / 点卡外):卡转进淡出。
        close();
    }
}
