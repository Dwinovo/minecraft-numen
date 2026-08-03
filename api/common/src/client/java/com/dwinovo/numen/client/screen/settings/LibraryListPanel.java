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
        Button add = ui.add(new Button(t(addKey), Button.Style.ACCENT, onAdd));
        add.setBounds(x + w - 56, y - 2, 56, NumenStyle.CONTROL_H);

        if (titleAction != null) {
            Button act = ui.add(new Button(titleActionLabel, Button.Style.NORMAL, () -> {
                titleAction.run();
                refresh();   // 动作(重扫等)可能改变条目集,当场刷新
            }));
            act.setBounds(x + w - 56 - 6 - 18, y - 2, 18, NumenStyle.CONTROL_H);
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
        refresh();
        list.scrollBy(keepScroll);   // 重建(换主题/改窗口)不丢滚动位
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
        boolean overEdit = hovered && inZone(rx, rw, EDIT_ZONE, DEL_ZONE);
        s.drawText("✎", rx + rw - EDIT_ZONE + 2, iconY,
                overEdit ? c.accent() : c.textMuted(), false);
        s.drawText("✕", rx + rw - DEL_ZONE + 2, iconY,
                overDel ? c.danger() : c.textMuted(), false);
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
            }
            return true;
        }
        if (xInRow >= listW - DEL_ZONE) {
            askDelete(e);
            return true;
        }
        onEdit.accept(e);   // ✎ 与行体同义:点行即编辑
        return true;
    }

    private void askDelete(T e) {
        confirm.open(ui, dimX, dimY, dimW, dimH, deleteMessage.apply(e),
                t("numen.gui.settings.cancel"), t("numen.dismiss.delete"),
                () -> {
                    onDeleteConfirmed.accept(e);
                    refresh();
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
