package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.ConfirmDialog;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.ListView;
import com.dwinovo.numen.client.ui.widget.Toggle;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 具名条目库的列表分区(NumenUI):标题行+新建(可选全局开关)、条目行
 * (名称/元信息/✎✕ 热区/可选行首绑定 ●)、删除走 {@link ConfirmDialog} 模态闸。
 * 模型配置/声线/皮肤同构——差异全在构造参数(取数、行文案、删除动作),
 * 面板只管几何与交互。行体与 ✎ 同义(点行即编辑);✕ 先确认再删。
 */
public final class LibraryListPanel<T> {

    /** 一行的展示数据(每帧按条目现算,绑定标记等活状态即时反映)。
     *  {@code marked}:TRUE=行左缘 accent 侧条(本同伴绑定中);null/FALSE=无标记。
     *  侧条不占缩进——行内容永远与无标记库同列对齐。
     *  {@code preset}=只读预设行:行尾 ⧉(克隆成自定义副本)替代 ✎✕,行体不可点。 */
    public record Row(String name, String meta, boolean metaDanger, Boolean marked, boolean preset) {

        public Row(String name, String meta, boolean metaDanger, Boolean marked) {
            this(name, meta, metaDanger, marked, false);
        }
    }

    private static final int ROW_H = 24;
    /** 行尾图标热区左缘距行右缘:✕ 最右,✎ 在其左(与旧版热区同宽,肌肉记忆不换)。 */
    private static final int DEL_ZONE = 14;
    private static final int EDIT_ZONE = 26;

    private final UiRoot ui = new UiRoot();
    private final ConfirmDialog confirm = new ConfirmDialog();
    /** 列表页轻回执(四层提示制式第③层):删除/克隆/签名成功一句话,自动淡出。
     *  跨 build 持久(host.rebuild 不吞在途消息)。 */
    private final com.dwinovo.numen.client.ui.widget.InlineAlert notice =
            new com.dwinovo.numen.client.ui.widget.InlineAlert();
    private final String titleKey;
    private final String addKey;
    private final String emptyKey;
    private final Supplier<List<T>> source;
    private final Function<T, Row> rowOf;
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
    // 可选的行内开关(MCP 服务器的启停):画在 ✕ 左侧,替代 ✎(行体点击仍=编辑)。
    private java.util.function.Predicate<T> toggleOn;
    private Consumer<T> toggleFlip;
    /** 行内开关热区左缘距行右缘(占据 ✎ 的位置再宽些)。 */
    private static final int TOGGLE_ZONE = 40;
    // 可选的预设行克隆动作(人格库的 ⧉)。
    private Consumer<T> onClone;
    // 可选的标题行附加按钮(人格库的 ↻ 重扫)。
    private String titleActionLabel;
    private Runnable titleAction;

    private ListView<T> list;
    private Label emptyLabel;
    private List<T> entries = List.of();
    private int listW;
    private int dimX, dimY, dimW, dimH;
    private int mouseX = -10000, mouseY = -10000;

    public LibraryListPanel(String titleKey, String addKey, String emptyKey,
                            Supplier<List<T>> source, Function<T, Row> rowOf,
                            Function<T, String> deleteMessage, Consumer<T> onDeleteConfirmed,
                            Runnable onAdd, Consumer<T> onEdit) {
        this.titleKey = titleKey;
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

    /** 行内启停开关(✕ 左侧的小胶囊,替代 ✎ 图标;行体点击仍=编辑)。 */
    public LibraryListPanel<T> withRowToggle(java.util.function.Predicate<T> isOn, Consumer<T> flip) {
        this.toggleOn = isOn;
        this.toggleFlip = flip;
        return this;
    }

    /** 鼠标悬停的行条目(仅行体,行尾动作热区不算)——宿主 render 末尾取来画 tooltip。 */
    public T entryAtBody(double mx, double my) {
        if (list == null || ui.hasOverlay()) return null;
        int row = list.rowAt(my);
        if (row < 0 || row >= entries.size() || !list.contains(mx, my)) return null;
        double xInRow = mx - list.x();
        int actionFrom = toggleOn != null ? TOGGLE_ZONE : EDIT_ZONE;
        if (xInRow >= listW - actionFrom) return null;
        return entries.get(row);
    }

    /** 行首图标列(条目自绘,如皮肤脸);行内容右移让位。 */
    public LibraryListPanel<T> withRowIcon(int size, RowIcon<T> icon) {
        this.rowIconSize = size;
        this.rowIcon = icon;
        return this;
    }

    /** 预设行(Row.preset)的 ⧉ 动作:克隆成可编辑副本并刷新列表。 */
    public LibraryListPanel<T> withPresetClone(Consumer<T> onClone) {
        this.onClone = onClone;
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

        Label title = ui.add(new Label(t(titleKey), Label.Role.PRIMARY));
        title.setBounds(x, y, w - 70, 9);
        int actionRight = x + w;   // 标题行按钮从右往左排
        if (addKey != null) {
            Button add = ui.add(new Button(t(addKey), Button.Style.ACCENT, onAdd));
            add.setBounds(x + w - 56, y - 2, 56, NumenStyle.CONTROL_H);
            actionRight = x + w - 56 - 6;
        }
        if (titleAction != null) {
            // 宽随文案实测(写死会被长文案穿底/盖住邻钮)。
            int aw = Math.max(18, Minecraft.getInstance().font.width(titleActionLabel) + 12);
            Button act = ui.add(new Button(titleActionLabel, Button.Style.NORMAL, () -> {
                titleAction.run();
                refresh();   // 动作(重扫等)可能改变条目集,当场刷新
            }));
            act.setBounds(actionRight - aw, y - 2, aw, NumenStyle.CONTROL_H);
        }

        if (toggleGet != null) {
            Toggle tog = ui.add(new Toggle(toggleGet.get(), toggleSet));
            int togX = x + w - 56 - 8 - 22;
            tog.setBounds(togX, y - 1, 22, 11);
            String label = t(toggleLabelKey);
            int lw = Minecraft.getInstance().font.width(label);
            Label togLabel = ui.add(new Label(label, Label.Role.MUTED));
            // 宽度=实测文本宽:标签后加在按钮之上,虚宽会盖住右侧新建钮吞掉点击。
            togLabel.setBounds(togX - lw - 4, y, lw, 9);
        }

        emptyLabel = ui.add(new Label(t(emptyKey), Label.Role.MUTED));
        emptyLabel.setBounds(x, y + 18, w, 9);

        list = ui.add(new ListView<T>(entries, ROW_H, this::renderRow, null)
                .rowClick(this::rowClicked));
        list.setBounds(x, y + 16, w, h - 16);
        ui.add(notice).setBounds(x, y + 18, w, 24);   // 列表顶部悬浮,永不参与命中
        refresh();
        list.scrollBy(keepScroll);   // 重建(换主题/改窗口)不丢滚动位
    }

    /** 列表页操作回执:成功绿胶囊 2.5s 自动淡出(宿主的异步动作完成后也可投递)。 */
    public void noticeSuccess(String message) {
        notice.show(com.dwinovo.numen.client.ui.widget.InlineAlert.Severity.SUCCESS, message, 2_500);
    }

    public void render(IDrawSurface s, NumenTheme.Colors c, int mx, int my, long nowMs) {
        if (list == null) return;
        this.mouseX = mx;
        this.mouseY = my;
        ui.render(s, c, mx, my, nowMs);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        return list != null && ui.mouseClicked(mx, my, button);
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        return list != null && ui.mouseScrolled(mx, my, delta);
    }

    /** ESC 关删除确认(= 取消);其余键列表不吃。 */
    public boolean keyPressed(int keyCode, int modifiers) {
        return list != null && ui.keyPressed(keyCode, modifiers);
    }

    // ---- 内部 ----

    private void refresh() {
        entries = source.get();
        list.setItems(entries);
        emptyLabel.setVisible(entries.isEmpty());
    }

    private void renderRow(IDrawSurface s, NumenTheme.Colors c, T e, int index,
                           int rx, int ry, int rw, int rh, boolean selected, boolean hovered) {
        Row row = rowOf.apply(e);
        if (hovered) s.fillRoundRect(rx, ry, rw, rh, NumenStyle.RADIUS_SMALL, c.hover());
        if (Boolean.TRUE.equals(row.marked())) {
            // 绑定标记 = 行左缘 accent 侧条,不占缩进(行内容与无标记库同列对齐)。
            s.fillRect(rx, ry + 2, 2, rh - 4, c.accent());
        }
        int tx = rx + 4;
        if (rowIcon != null) {
            rowIcon.draw(s, e, rx + 2, ry + (rh - rowIconSize) / 2, rowIconSize);
            tx = rx + 2 + rowIconSize + 4;
        }
        s.drawText(row.name() == null ? "" : row.name(), tx, ry + 3, c.textPrimary(), false);
        s.drawText(clip(s, row.meta(), rw - EDIT_ZONE - 6 - (tx - rx)), tx, ry + 13,
                row.metaDanger() ? c.danger() : c.textMuted(), false);

        int iconY = ry + (rh - s.lineHeight()) / 2 + 1;
        boolean overDel = hovered && inZone(rx, rw, DEL_ZONE, 0);
        if (row.preset()) {   // 只读预设:行尾唯一动作 = ⧉ 克隆成副本
            s.drawText("⧉", rx + rw - DEL_ZONE + 2, iconY,
                    overDel ? c.accent() : c.textMuted(), false);
            return;
        }
        if (toggleOn != null) {
            // 行内启停:静态小胶囊(逐行控件实例进不了 ListView 的渲染回调,画出来即可)。
            boolean on = toggleOn.test(e);
            int tx0 = rx + rw - (deleteMessage != null ? TOGGLE_ZONE : 24);
            int ty = ry + (rh - 10) / 2;
            s.fillRoundRect(tx0, ty, 20, 10, 5, on ? c.accent() : c.inputBg());
            s.fillRoundRect(on ? tx0 + 11 : tx0 + 2, ty + 2, 7, 6, 3, 0xFFFFFFFF);
        } else if (deleteMessage != null) {
            boolean overEdit = hovered && inZone(rx, rw, EDIT_ZONE, DEL_ZONE);
            s.drawText("✎", rx + rw - EDIT_ZONE + 2, iconY,
                    overEdit ? c.accent() : c.textMuted(), false);
        }
        if (deleteMessage != null) {
            s.drawText("✕", rx + rw - DEL_ZONE + 2, iconY,
                    overDel ? c.danger() : c.textMuted(), false);
        }
    }

    /** 鼠标横坐标是否落在距行右缘 [from, to) 的图标热区(from > to,都是距右缘距离)。 */
    private boolean inZone(int rx, int rw, int from, int to) {
        return mouseX >= rx + rw - from && mouseX < rx + rw - to;
    }

    private boolean rowClicked(int index, double xInRow) {
        if (index < 0 || index >= entries.size()) return false;
        T e = entries.get(index);
        if (rowOf.apply(e).preset()) {   // 预设行:只有 ⧉ 热区有动作,行体吞掉不编辑
            if (xInRow >= listW - DEL_ZONE && onClone != null) {
                onClone.accept(e);
                refresh();
                noticeSuccess(t("numen.gui.list.cloned"));
            }
            return true;
        }
        if (deleteMessage != null && xInRow >= listW - DEL_ZONE) {
            askDelete(e);
            return true;
        }
        if (toggleOn != null && xInRow >= listW - TOGGLE_ZONE) {
            toggleFlip.accept(e);
            return true;
        }
        onEdit.accept(e);   // ✎ 与行体同义:点行即编辑(纯开关库传空实现)
        return true;
    }

    private void askDelete(T e) {
        confirm.open(ui, dimX, dimY, dimW, dimH, deleteMessage.apply(e),
                t("numen.gui.settings.cancel"), t("numen.dismiss.delete"),
                () -> {
                    String name = rowOf.apply(e).name();
                    onDeleteConfirmed.accept(e);
                    refresh();
                    noticeSuccess(net.minecraft.network.chat.Component
                            .translatable("numen.gui.list.deleted", name).getString());
                });
    }

    private static String clip(IDrawSurface s, String text, int maxW) {
        if (s.textWidth(text) <= maxW) return text;
        String cut = text;
        while (!cut.isEmpty() && s.textWidth(cut + "…") > maxW) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    private static String t(String key) {
        return I18n.get(key);
    }
}
