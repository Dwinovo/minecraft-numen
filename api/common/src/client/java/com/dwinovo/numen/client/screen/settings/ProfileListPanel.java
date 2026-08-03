package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.agent.llm.ProviderLibrary;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.ConfirmDialog;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.ListView;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.data.ModLanguageData;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 模型配置列表(NumenUI):标题行+新建、档案行(名称/元信息/✎/✕)、删除走
 * {@link ConfirmDialog} 模态闸。行体与 ✎ 同义(点行即编辑);✕ 先确认再删,
 * 确认卡开着时整个面板的点击都被浮层吞掉(模态语义由 UiRoot 保证)。
 */
public final class ProfileListPanel {

    private static final int ROW_H = 24;
    /** 行尾图标热区左缘距行右缘:✕ 最右,✎ 在其左(与旧版热区同宽,肌肉记忆不换)。 */
    private static final int DEL_ZONE = 14;
    private static final int EDIT_ZONE = 26;

    private final UiRoot ui = new UiRoot();
    private final ConfirmDialog confirm = new ConfirmDialog();
    private final Runnable onAdd;
    private final Consumer<ProviderLibrary.Entry> onEdit;

    private ListView<ProviderLibrary.Entry> list;
    private Label emptyLabel;
    private List<ProviderLibrary.Entry> entries = List.of();
    private int listW;
    private int dimX, dimY, dimW, dimH;
    private int mouseX = -10000, mouseY = -10000;

    public ProfileListPanel(Runnable onAdd, Consumer<ProviderLibrary.Entry> onEdit) {
        this.onAdd = onAdd;
        this.onEdit = onEdit;
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

        Label title = ui.add(new Label(t(ModLanguageData.Keys.PROVIDER_TITLE), Label.Role.PRIMARY));
        title.setBounds(x, y, w - 70, 9);
        Button add = ui.add(new Button(t(ModLanguageData.Keys.PROVIDER_ADD), Button.Style.ACCENT, onAdd));
        add.setBounds(x + w - 56, y - 2, 56, NumenStyle.CONTROL_H);

        emptyLabel = ui.add(new Label(t(ModLanguageData.Keys.PROVIDER_EMPTY), Label.Role.MUTED));
        emptyLabel.setBounds(x, y + 18, w, 9);

        list = ui.add(new ListView<ProviderLibrary.Entry>(entries, ROW_H, this::renderRow, null)
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
        entries = ProviderLibrary.instance().list();
        list.setItems(entries);
        emptyLabel.setVisible(entries.isEmpty());
    }

    private void renderRow(IDrawSurface s, NumenTheme.Colors c, ProviderLibrary.Entry e, int index,
                           int rx, int ry, int rw, int rh, boolean selected, boolean hovered) {
        if (hovered) s.fillRoundRect(rx, ry, rw, rh, NumenStyle.RADIUS_SMALL, c.hover());
        s.drawText(e.name() == null ? "" : e.name(), rx + 2, ry + 3, c.textPrimary(), false);
        boolean hasKey = nb(e.apiKey());
        String meta = (nb(e.provider()) ? e.provider() : "?") + " · "
                + (nb(e.model()) ? e.model() : "?")
                + (hasKey ? "" : " · " + t(ModLanguageData.Keys.PROVIDER_NO_KEY));
        // 元信息用次级色不用最淡档——这是内容不是装饰,淡到读不清等于没写。
        s.drawText(clip(s, meta, rw - EDIT_ZONE - 6), rx + 2, ry + 13,
                hasKey ? c.textMuted() : c.danger(), false);

        int iconY = ry + (rh - s.lineHeight()) / 2 + 1;
        boolean overEdit = hovered && inZone(rx, rw, EDIT_ZONE, DEL_ZONE);
        boolean overDel = hovered && inZone(rx, rw, DEL_ZONE, 0);
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
        ProviderLibrary.Entry e = entries.get(index);
        if (xInRow >= listW - DEL_ZONE) {
            askDelete(e);
            return true;
        }
        onEdit.accept(e);   // ✎ 与行体同义:点行即编辑
        return true;
    }

    private void askDelete(ProviderLibrary.Entry e) {
        confirm.open(ui, dimX, dimY, dimW, dimH,
                Component.translatable(ModLanguageData.Keys.PROVIDER_DELETE_CONFIRM,
                        e.name() == null ? "" : e.name()).getString(),
                t("numen.gui.settings.cancel"), t("numen.dismiss.delete"),
                () -> {
                    ProviderLibrary.instance().remove(e.id());
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

    private static boolean nb(String s) {
        return s != null && !s.isBlank();
    }

    private static String t(String key) {
        return I18n.get(key);
    }
}
