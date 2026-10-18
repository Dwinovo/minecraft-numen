package com.dwinovo.numen.api.client.screen.settings;

import com.dwinovo.numen.api.client.screen.PopupMenu;
import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.TextClip;
import com.dwinovo.numen.api.client.ui.mc.Sprites;
import com.dwinovo.numen.api.client.ui.widget.Button;
import com.dwinovo.numen.api.client.ui.widget.ConfirmDialog;
import com.dwinovo.numen.api.client.ui.widget.Label;
import com.dwinovo.numen.api.client.ui.widget.ListView;
import com.dwinovo.numen.api.client.ui.widget.Toggle;
import com.dwinovo.numen.api.client.ui.widget.UiRoot;
import com.dwinovo.numen.api.data.ModLanguageData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 具名条目库的列表分区(NumenUI):标题行+新建(可选全局开关)、条目行、右键菜单,
 * 删除走 {@link ConfirmDialog} 模态闸。模型配置/声线/人设/皮肤/工具扩展/技能同构——
 * 差异全在构造参数(取数、行文案、删除动作),面板只管几何与交互。
 *
 * <p>行照 Telegram 设置里的列表行:名字一行、说明淡字一行,行与行之间一道从文字左缘起的细线,
 * 悬停浅底。点行就是编辑(只读预设行点了是克隆);右键一行弹菜单:编辑、克隆、给她用、删除
 * ——删除标红放最下面,点了仍先过确认卡。当前同伴在用的那条在行右端挂一枚强调色的勾,
 * 和 Telegram 列表里选中项一个样子;换绑、解绑在右键菜单里,即时生效,不用遣散重召。
 */
public final class LibraryListPanel<T> {

    /** 一行的展示数据(每帧按条目现算,绑定标记等活状态即时反映)。
     *  {@code marked}:null = 没有选中的同伴(不谈绑定);TRUE = 当前同伴在用(行尾勾);FALSE = 没在用。
     *  {@code preset}=只读行(玩家对内置只读):点行复制到我的,菜单里没有编辑与删除。
     *  {@code canClone}=菜单里有"复制到我的"(内置的行:管理员的可编辑,玩家的只读,都能复制)。
     *  {@code toggle}=该行有行尾开关(库配了行开关时;不是库条目的行没有)。 */
    public record Row(String name, String meta, boolean metaDanger, Boolean marked, boolean preset, boolean canClone,
                      boolean toggle) {

        public Row(String name, String meta, boolean metaDanger, Boolean marked) {
            this(name, meta, metaDanger, marked, false, false, true);
        }

        public Row(String name, String meta, boolean metaDanger, Boolean marked, boolean preset) {
            this(name, meta, metaDanger, marked, preset, preset, true);
        }

        public Row(String name, String meta, boolean metaDanger, Boolean marked, boolean preset, boolean canClone) {
            this(name, meta, metaDanger, marked, preset, canClone, true);
        }

        /** 这一行不是库里的条目(排队中的签名):没有行尾开关。 */
        public Row withoutToggle() {
            return new Row(name, meta, metaDanger, marked, preset, canClone, false);
        }
    }

    /** 列表里的一行:条目,或分组的标题(两组并排时,"内置"与"我的"各一条标题行)。 */
    private record Line<T>(T item, String heading) {

        boolean isHeading() {
            return item == null;
        }
    }

    private static final int ROW_H = 26;
    /** 行内容离行左右两缘的距离。 */
    private static final int ROW_INSET = 4;
    /** 行尾开关的几何,点击热区是它左边再宽出一截到行右缘。 */
    private static final int TOGGLE_W = 20;
    private static final int TOGGLE_H = 10;
    private static final int TOGGLE_ZONE = TOGGLE_W + ROW_INSET + 4;
    /** 行尾那样东西(勾/开关)到文字的间距。 */
    private static final int TAIL_GAP = 6;
    /** 右键菜单往右长开要留出的宽;右边放不下就往左长(与 NumenScreen 的右键菜单同一个判据值)。 */
    private static final int MENU_ROOM = 120;
    private static final Button.IconDrawer CHECK = Sprites.painter(Sprites.CHECK);

    private final UiRoot ui = new UiRoot();
    private final ConfirmDialog confirm = new ConfirmDialog();
    private final PopupMenu menu = new PopupMenu(Minecraft.getInstance().font);
    /** 右键点在哪:菜单开着时这一行一直亮着悬停底(Telegram 的右键菜单也这样指明作用在哪一行)。 */
    private int menuX, menuY;
    /** 列表页轻回执(四层提示制式第③层):删除/克隆/签名成功一句话,自动淡出。
     *  跨 build 持久(host.rebuild 不吞在途消息)。 */
    private final com.dwinovo.numen.api.client.ui.widget.InlineAlert notice =
            new com.dwinovo.numen.api.client.ui.widget.InlineAlert();
    private final String addKey;
    private final String emptyKey;
    private final Supplier<List<T>> source;
    private final Function<T, Row> rowOf;
    /** 条目属于哪一组(标题文字);组变了就插一行标题。没有分组是 {@code null}。 */
    private Function<T, String> groupOf;
    /** 某一行右键菜单里除了通用几项以外的(如管理员采用内置的新版)。 */
    private Function<T, List<PopupMenu.Item>> extraMenu;
    private final Function<T, String> deleteMessage;
    private final Consumer<T> onDeleteConfirmed;
    private final Runnable onAdd;
    private final Consumer<T> onEdit;

    // 可选的标题行全局开关(声线库的"启用语音")。
    private String toggleLabelKey;
    private Supplier<Boolean> toggleGet;
    private Consumer<Boolean> toggleSet;
    /** 可选的行首图标回调(皮肤库的脸预览);MC 专属绘制经 McDrawSurface 降级取原生画布。 */
    public interface RowIcon<T> {
        void draw(IDrawSurface s, T item, int x, int y, int size);
    }

    private RowIcon<T> rowIcon;
    private int rowIconSize;
    /** 行内开关的滑动动画:一次只翻一行,记住它并逐帧趋近(其余行取静态位置)。
     *  行控件实例进不了 ListView 的渲染回调,动画状态只能由面板自己拿着。 */
    private int animRow = -1;
    private float animKnob;
    private long lastAnimMs = -1;
    // 可选的行内开关(MCP 服务器的启停):画在行右端。
    private java.util.function.Predicate<T> toggleOn;
    private Consumer<T> toggleFlip;
    // 可选的克隆动作(人设库):菜单里的"克隆",也是预设行点下去做的事。
    private Consumer<T> onClone;
    // 可选的绑定动作:给当前同伴用;onUnbind 为 null 的库不许解绑(必须有一个)。
    private Consumer<T> onBind;
    private Consumer<T> onUnbind;
    // 可选的标题行附加按钮(人格库的 ↻ 重扫)。
    private String titleActionLabel;
    private Runnable titleAction;

    private ListView<Line<T>> list;
    private Label emptyLabel;
    private List<T> entries = List.of();
    private List<Line<T>> lines = List.of();
    /** 这张表在客户端的版本(服务端推来一张、或本地先画了删除就变);没有 = 本地的库,只在动作之后刷新。 */
    private java.util.function.LongSupplier version;
    private java.util.function.LongSupplier revision;
    private Supplier<String> problem;
    private long seenVersion;
    private long seenRevision;
    /** 管理员才有的第二个"新建":建到内置里(所有玩家都用得到);没有这回事的库是 {@code null}。 */
    private Runnable onAddBuiltin;
    private int listW;
    private int dimX, dimY, dimW, dimH;

    public LibraryListPanel(String addKey, String emptyKey,
                            Supplier<List<T>> source, Function<T, Row> rowOf,
                            Function<T, String> deleteMessage,
                            Consumer<T> onDeleteConfirmed,
                            Runnable onAdd, Consumer<T> onEdit) {
        this.addKey = addKey;
        this.emptyKey = emptyKey;
        this.source = source;
        this.rowOf = rowOf;
        this.deleteMessage = deleteMessage;
        this.onDeleteConfirmed = onDeleteConfirmed;
        this.onAdd = onAdd;
        this.onEdit = onEdit;
    }

    public LibraryListPanel<T> withToggle(String labelKey, Supplier<Boolean> get, Consumer<Boolean> set) {
        this.toggleLabelKey = labelKey;
        this.toggleGet = get;
        this.toggleSet = set;
        return this;
    }

    /** 条目按组排开:{@code groupOf} 给每个条目的组名,相邻的同组条目前面插一行标题;条目要按组排好序给。 */
    public LibraryListPanel<T> withGroups(Function<T, String> groupOf) {
        this.groupOf = groupOf;
        return this;
    }

    /** 右键菜单里再加几项(在通用几项的前面,与它们之间一道分隔线)。 */
    public LibraryListPanel<T> withMenu(Function<T, List<PopupMenu.Item>> extra) {
        this.extraMenu = extra;
        return this;
    }

    /** 行内启停开关(行右端的小开关;有编辑的库行体点击仍=编辑,没有的整行都是开关)。 */
    public LibraryListPanel<T> withRowToggle(java.util.function.Predicate<T> isOn, Consumer<T> flip) {
        this.toggleOn = isOn;
        this.toggleFlip = flip;
        return this;
    }

    /** 鼠标悬停的行条目(仅行体,行尾开关热区不算)——宿主 render 末尾取来画 tooltip。 */
    public T entryAtBody(double mx, double my) {
        if (list == null || ui.hasOverlay()) return null;
        int row = list.rowAt(my);
        if (row < 0 || row >= lines.size() || !list.contains(mx, my) || lines.get(row).isHeading()) return null;
        if (toggleOn != null && mx - list.x() >= listW - TOGGLE_ZONE) return null;
        return lines.get(row).item();
    }

    /** 行首图标列(条目自绘,如皮肤脸);行内容右移让位。 */
    public LibraryListPanel<T> withRowIcon(int size, RowIcon<T> icon) {
        this.rowIconSize = size;
        this.rowIcon = icon;
        return this;
    }

    /** 克隆成可编辑副本并刷新列表:右键菜单里的"克隆",也是只读预设行点下去做的事。 */
    /**
     * 列表读的是服务器上这一种库:它的版本一变这一帧就重读,没变就一直用手里这份;服务端推来的表带着"没办成"的原因时
     * (存不下、排不进队……)在列表顶上亮一条错误,不让玩家以为办成了。
     */
    public LibraryListPanel<T> following(String kind) {
        var library = com.dwinovo.numen.api.client.agent.RemoteCompanions.library();
        this.version = () -> library.version(kind);
        this.revision = () -> library.revision(kind);
        this.problem = () -> library.problem(kind);
        return this;
    }

    /** 管理员多一个"新建内置":表单建出来的条目放进服务器目录的内置组,所有玩家都用得到。 */
    public LibraryListPanel<T> withAddBuiltin(Runnable onAddBuiltin) {
        this.onAddBuiltin = onAddBuiltin;
        return this;
    }

    public LibraryListPanel<T> withClone(Consumer<T> onClone) {
        this.onClone = onClone;
        return this;
    }

    /**
     * 绑定:{@code onBind} 把该条目给当前同伴用(即时生效);{@code onUnbind} 为 null 表示这库
     * 不许解绑(如模型档案——同伴必须有一个端点)。只在 Row.marked 非 null(= 有同伴被选中)的行
     * 出现在右键菜单里。
     */
    public LibraryListPanel<T> withBind(Consumer<T> onBind, Consumer<T> onUnbind) {
        this.onBind = onBind;
        this.onUnbind = onUnbind;
        return this;
    }

    /** 标题行附加按钮(新建左侧的小方钮,如人格库的 ↻ 重扫)。 */
    public LibraryListPanel<T> withTitleAction(String label, Runnable action) {
        this.titleActionLabel = label;
        this.titleAction = action;
        return this;
    }

    /** {@code x,y} = 标题行左上;{@code dim*} = 删除确认的暗幕覆盖区(整块设置面板)。 */
    public void build(int x, int y, int w, int h, int dimX, int dimY, int dimW, int dimH) {
        this.dimX = dimX;
        this.dimY = dimY;
        this.dimW = dimW;
        this.dimH = dimH;
        this.listW = w;
        double keepScroll = list != null ? list.scrollY() : 0;
        ui.clear();
        menu.closeOverlay();   // 浮层通道随重建清空了,开着的菜单跟着收起(淡出)

        // 这一页叫什么写在面板抬头上(Telegram 子页),这一行只放动作:左端"＋ 新建",右端开关与其它动作
        var font = Minecraft.getInstance().font;
        if (addKey != null) {
            // 新建是轻的:平时只有字,悬停才浮出底(Telegram 列表顶上的"添加"不是一整块色)
            String addLabel = "+ " + t(addKey);
            Button add = ui.add(new Button(addLabel, Button.Style.GHOST, onAdd));
            add.setBounds(x, y, font.width(addLabel) + 12, NumenStyle.HEADER_H);
            if (onAddBuiltin != null && com.dwinovo.numen.api.client.agent.RemoteCompanions.admin()) {
                String builtinLabel = "+ " + t(ModLanguageData.Keys.LIBRARY_ADD_BUILTIN);
                Button addBuiltin = ui.add(new Button(builtinLabel, Button.Style.GHOST, onAddBuiltin));
                addBuiltin.setBounds(x + font.width(addLabel) + 12, y, font.width(builtinLabel) + 12, NumenStyle.HEADER_H);
            }
        }
        int actionRight = x + w;   // 右端的动作从右往左排
        if (titleAction != null) {
            // 宽随文案实测(写死会被长文案穿底/盖住邻钮)。
            int aw = Math.max(18, font.width(titleActionLabel) + 12);
            Button act = ui.add(new Button(titleActionLabel, Button.Style.NORMAL, () -> {
                titleAction.run();
                refresh();   // 动作(重扫等)可能改变条目集,当场刷新
            }));
            act.setBounds(actionRight - aw, y, aw, NumenStyle.HEADER_H);
            actionRight -= aw + 6;
        }

        if (toggleGet != null) {
            Toggle tog = ui.add(new Toggle(toggleGet.get(), toggleSet));
            int togX = actionRight - 22;
            tog.setBounds(togX, NumenStyle.centerIn(y, NumenStyle.HEADER_H, 11), 22, 11);
            String label = t(toggleLabelKey);
            int lw = font.width(label);
            Label togLabel = ui.add(new Label(label, Label.Role.SECONDARY));
            // 宽度=实测文本宽:标签后加在按钮之上,虚宽会盖住右侧新建钮吞掉点击。
            togLabel.setBounds(togX - lw - 4, NumenStyle.centerIn(y, NumenStyle.HEADER_H, 9), lw, 9);
        }

        int body = NumenStyle.bodyTop(y);
        emptyLabel = ui.add(new Label(t(emptyKey), Label.Role.SECONDARY));
        emptyLabel.setBounds(x, body + 2, w, 9);

        list = ui.add(new ListView<Line<T>>(lines, ROW_H, this::renderRow, null)
                .rowClick(this::rowClicked));
        list.setBounds(x, body, w, y + h - body);
        ui.add(notice).setBounds(x, body + 2, w, 24);   // 列表顶部悬浮,永不参与命中
        refresh();
        list.scrollBy(keepScroll);   // 重建(换主题/改窗口)不丢滚动位
    }

    /** 列表页操作回执:成功绿胶囊 2.5s 自动淡出(宿主的异步动作完成后也可投递)。 */
    public void noticeSuccess(String message) {
        notice.show(com.dwinovo.numen.api.client.ui.widget.InlineAlert.Severity.SUCCESS, message, 2_500);
    }

    public void render(IDrawSurface s, NumenTheme.Colors c, int mx, int my, long nowMs) {
        if (list == null) return;
        if (version != null && version.getAsLong() != seenVersion) {
            boolean pushed = revision != null && revision.getAsLong() != seenRevision;
            refresh();   // 服务端推来了新表,这一帧就跟上
            if (pushed && problem.get() != null) {
                notice.show(com.dwinovo.numen.api.client.ui.widget.InlineAlert.Severity.ERROR, problem.get());
            }
        }
        advanceRowToggleAnim(nowMs);
        // 菜单开着:底下的行按右键那一点取悬停,被右键的那一行一直亮着;菜单自己按真实指针走
        boolean menuUp = menu.isOpen();
        ui.renderContent(s, c, menuUp ? menuX : mx, menuUp ? menuY : my, nowMs);
        ui.renderOverlayLayer(s, c, mx, my, nowMs);
    }

    /** 帧间隔归一化的趋近(与 Toggle 组件同一节律);到位即释放动画行。 */
    private void advanceRowToggleAnim(long nowMs) {
        long dt = lastAnimMs < 0 ? 16 : nowMs - lastAnimMs;
        lastAnimMs = nowMs;
        if (animRow < 0 || animRow >= lines.size() || lines.get(animRow).isHeading()) {
            animRow = -1;
            return;
        }
        float target = toggleOn.test(lines.get(animRow).item()) ? 1f : 0f;
        float speed = Math.min(1f, dt / 16f * 0.35f);
        animKnob = com.dwinovo.numen.api.client.ui.Animation.lerpTo(animKnob, target, speed, 0.01f);
        if (Math.abs(animKnob - target) < 0.01f) animRow = -1;
    }

    /** 左键照常分发;右键在一行上弹出这一行的菜单。 */
    public boolean mouseClicked(double mx, double my, int button) {
        if (list == null) return false;
        if (button != 1) return ui.mouseClicked(mx, my, button);
        if (ui.hasOverlay()) {
            // 菜单开着:右键点在菜单外就是收起(同左键);确认卡开着:卡上的键只认左键,右键吞掉
            return !menu.isOpen() || ui.mouseClicked(mx, my, button);
        }
        int index = list.contains(mx, my) ? list.rowAt(my) : -1;
        if (index < 0 || index >= lines.size() || lines.get(index).isHeading()) return false;
        List<PopupMenu.Item> items = menuItems(lines.get(index).item());
        if (items.isEmpty()) return false;
        menuX = (int) mx;
        menuY = (int) my;
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        menu.open(ui, items, menuX, menuY, mx + MENU_ROOM < screenW);
        return true;
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        return list != null && ui.mouseScrolled(mx, my, delta);
    }

    /** ESC 收起菜单、关删除确认(= 取消);其余键列表不吃。 */
    public boolean keyPressed(int keyCode, int modifiers) {
        return list != null && ui.keyPressed(keyCode, modifiers);
    }

    // ---- 内部 ----

    /** 重读条目换进列表:动作之后、建面板时,以及跟着的那种库版本变了的那一帧。 */
    private void refresh() {
        if (version != null) {
            seenVersion = version.getAsLong();
            seenRevision = revision.getAsLong();
        }
        entries = source.get();
        List<Line<T>> next = new ArrayList<>();
        String group = null;
        for (T e : entries) {
            String now = groupOf == null ? null : groupOf.apply(e);
            if (now != null && !now.equals(group)) {
                next.add(new Line<>(null, now));
            }
            group = now;
            next.add(new Line<>(e, null));
        }
        lines = next;
        list.setItems(lines);
        emptyLabel.setVisible(entries.isEmpty());
    }

    private void renderRow(IDrawSurface s, NumenTheme.Colors c, Line<T> line, int index,
                           int rx, int ry, int rw, int rh, boolean selected, boolean hovered) {
        if (line.isHeading()) {
            // 分组标题:淡字压在行的下沿,和下面第一条贴着
            s.drawText(TextClip.fit(s, line.heading(), rw - 2 * ROW_INSET), rx + ROW_INSET, ry + rh - 12,
                    c.textMuted(), false);
            return;
        }
        T e = line.item();
        Row row = rowOf.apply(e);   // 行悬停底由 ListView 统一画(带淡入),这里只画内容
        int tx = rx + ROW_INSET;
        if (rowIcon != null) {
            rowIcon.draw(s, e, rx + 2, ry + (rh - rowIconSize) / 2, rowIconSize);
            tx = rx + 2 + rowIconSize + 4;
        }
        int tail = rx + rw - ROW_INSET;   // 行尾那样东西的右缘
        int textRight = tail;
        if (toggleOn != null && row.toggle()) {
            // 行内启停:与 Toggle 组件同制——关闭态描边环+灰轨道(浅面板上不隐形),
            // 翻转时滑块滑动、轨道色随之渐变(动画状态由面板持有,见 animRow)。
            boolean on = toggleOn.test(e);
            float knob = index == animRow ? animKnob : (on ? 1f : 0f);
            int tx0 = tail - TOGGLE_W;
            int ty = ry + (rh - TOGGLE_H) / 2;
            s.fillRect(tx0, ty, TOGGLE_W, TOGGLE_H,
                    NumenStyle.mixColor(c.textMuted(), c.accent(), knob));
            if (knob < 0.99f) {   // 关闭侧的浅底+灰纹随进度淡出
                int fade = (int) (255 * (1f - knob));
                s.fillRect(tx0 + 1, ty + 1, TOGGLE_W - 2, TOGGLE_H - 2,
                        (fade << 24) | (c.inputBg() & 0xFFFFFF));
                s.fillRect(tx0 + 1, ty + 1, TOGGLE_W - 2, TOGGLE_H - 2,
                        ((int) (0x40 * (1f - knob)) << 24) | (c.textMuted() & 0xFFFFFF));
            }
            s.fillRect(tx0 + 2 + Math.round(9 * knob), ty + 2, 7, 6, 0xFFFFFFFF);
            textRight = tx0 - TAIL_GAP;
        } else if (Boolean.TRUE.equals(row.marked())) {
            // 当前同伴在用:行右端一枚强调色的勾(Telegram 列表里选中项的样子)
            CHECK.draw(s, tail - Sprites.SIZE, ry + (rh - Sprites.SIZE) / 2,
                    Sprites.SIZE, c.accent());
            textRight = tail - Sprites.SIZE - TAIL_GAP;
        }
        int textW = textRight - tx;
        s.drawText(TextClip.fit(s, row.name() == null ? "" : row.name(), textW), tx, ry + 4,
                c.textPrimary(), false);
        s.drawText(TextClip.fit(s, row.meta(), textW), tx, ry + 15,
                row.metaDanger() ? c.danger() : c.textSecondary(), false);
        if (index < lines.size() - 1 && !lines.get(index + 1).isHeading()) {   // 行间细线从文字左缘起,最后一行与组尾不画
            s.fillRect(tx, ry + rh - 1, rx + rw - tx, 1, c.divider());
        }
    }

    private boolean rowClicked(int index, double xInRow) {
        if (index < 0 || index >= lines.size() || lines.get(index).isHeading()) return false;
        T e = lines.get(index).item();
        if (toggleOn != null && rowOf.apply(e).toggle() && (onEdit == null || xInRow >= listW - TOGGLE_ZONE)) {
            // 点开关翻转;没有编辑的库(技能)整行都是开关——Telegram 设置里带开关的行点哪儿都翻
            animRow = index;                                  // 从翻转前的位置起步滑动
            animKnob = toggleOn.test(e) ? 1f : 0f;
            toggleFlip.accept(e);
            return true;
        }
        if (rowOf.apply(e).preset()) {   // 只读行:点行 = 复制到我的
            if (onClone != null) clone(e);
            return true;
        }
        if (onEdit != null) onEdit.accept(e);
        return true;
    }

    /** 这一行的右键菜单:编辑、克隆、给她用/不再使用,删除标红放最下面、前面一道分隔线。 */
    private List<PopupMenu.Item> menuItems(T e) {
        Row row = rowOf.apply(e);
        List<PopupMenu.Item> items = new ArrayList<>();
        if (extraMenu != null) {
            items.addAll(extraMenu.apply(e));
            if (!items.isEmpty()) items.add(PopupMenu.SEPARATOR);
        }
        if (onEdit != null && !row.preset()) {
            items.add(new PopupMenu.Item(Sprites.EDIT, t(ModLanguageData.Keys.EDIT_TITLE), false,
                    () -> onEdit.accept(e)));
        }
        if (onClone != null && row.canClone()) {
            items.add(new PopupMenu.Item(Sprites.COPY, t("numen.menu.clone"), false, () -> clone(e)));
        }
        if (onBind != null && row.marked() != null) {
            if (!row.marked()) {
                items.add(new PopupMenu.Item(Sprites.CHECK, t("numen.menu.use"), false, () -> {
                    onBind.accept(e);
                    refresh();
                    noticeSuccess(net.minecraft.network.chat.Component
                            .translatable("numen.gui.list.bound", row.name()).getString());
                }));
            } else if (onUnbind != null) {
                items.add(new PopupMenu.Item(Sprites.CHECK, t("numen.menu.stop_using"), false, () -> {
                    onUnbind.accept(e);
                    refresh();
                    noticeSuccess(t("numen.gui.list.bind_cleared"));
                }));
            }
        }
        if (deleteMessage != null && !row.preset()) {
            if (!items.isEmpty()) items.add(PopupMenu.SEPARATOR);
            items.add(new PopupMenu.Item(Sprites.DELETE, t("numen.dismiss.delete"), true, () -> askDelete(e)));
        }
        return items;
    }

    private void clone(T e) {
        onClone.accept(e);
        refresh();
        noticeSuccess(t("numen.gui.list.cloned"));
    }

    private void askDelete(T e) {
        confirm.open(ui, dimX, dimY, dimW, dimH, deleteMessage.apply(e),
                t(ModLanguageData.Keys.GUI_SETTINGS_CANCEL), t("numen.dismiss.delete"),
                () -> {
                    String name = rowOf.apply(e).name();
                    onDeleteConfirmed.accept(e);
                    refresh();
                    noticeSuccess(net.minecraft.network.chat.Component
                            .translatable("numen.gui.list.deleted", name).getString());
                });
    }

    private static String t(String key) {
        return I18n.get(key);
    }
}
