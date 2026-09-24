package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.client.ui.KeyCodes;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.mc.Sprites;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.DialogBox;
import com.dwinovo.numen.client.ui.widget.Dropdown;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 面板里的居中卡:召唤({@link SummonPanel})、改她({@link CompanionEditPanel})、改会话名
 * ({@link ConversationEditPanel})、邀请({@link InvitePanel})。屏幕只认这一个面,暗幕、居中、
 * 出没动效({@link DialogBox})、事件转发都只写一份。
 *
 * <p>卡里的版式也只在这里定一份,照 Telegram 的对话框,四张卡只说自己有哪几行:
 * <ul>
 *   <li>左上一行加粗的标题({@link #title});</li>
 *   <li>头部({@link #COVER_H}):左边一张脸,右边是名字或一个输入框——Telegram 编辑联系人、新建联系人那一块;</li>
 *   <li>字段({@link #field}):淡色标签在上,下划线输入框在下;</li>
 *   <li>选择行({@link #select}):整行宽,左标签、右当前值,点开选;</li>
 *   <li>右下一排纯字钮({@link #buttons}):取消在左,主按钮在右。</li>
 * </ul>
 * 卡宽、边距、按钮行与确认卡共用 {@link DialogBox} 的那几个数;这里的几个数按同样的比例从 Telegram 折过来。
 *
 * <p>键盘也照 Telegram 的对话框,四张卡一样:Tab / Shift+Tab 在输入框之间走,回车 = 主按钮
 * (主按钮置灰时不动),Esc = 取消;下拉展开着时 Esc 先收下拉。
 */
abstract class ModalCard {

    /** 头部的高(infoProfileCover 108)与脸的边长(按 8 的整数倍取,像素脸才不糊)。 */
    static final int COVER_H = 44;
    static final int PHOTO = 32;
    /** 脸到右边名字、输入框的距离(nameLeft − photoLeft − 照片宽 = 18)。 */
    static final int PHOTO_GAP = 12;
    /** 字段:标签一行 + 输入框(defaultInputField 的 heightMin 52)。 */
    static final int FIELD_H = 26;
    private static final int FIELD_LABEL_H = 10;
    /** 选择行的高(settingsButton 一行约 40)。 */
    static final int ROW_H = 20;
    /** 正文和按钮行之间(box margin 下 10)。 */
    static final int BODY_BOTTOM = 5;

    protected final UiRoot ui = new UiRoot();
    /** 卡的外框(build 时由屏幕给)。 */
    protected int x, y, w, h;
    private String title;
    /** Tab 走的输入框,按排版的先后。 */
    private final List<TextField> fields = new ArrayList<>();
    /** 回车按的主按钮与它的动作、Esc 走的取消(都由 {@link #buttons} 登记)。 */
    private Button primary;
    private Runnable primaryAction;
    private Runnable cancelAction;

    /** 每次开卡:草稿从当下真相取一次基线。 */
    abstract void reset();

    /** 卡想要的宽;屏幕按面板宽度封顶后居中。带字段的卡是 Telegram 的宽卡。 */
    int width() {
        return DialogBox.WIDE_WIDTH;
    }

    /** 卡的高度;屏幕据此居中。用 {@link #heightFor} 从正文高算。 */
    abstract int height();

    /** 标题行 + 正文 {@code bodyH} + 按钮行。 */
    static int heightFor(int bodyH) {
        return DialogBox.TITLE_H + bodyH + BODY_BOTTOM + DialogBox.FOOTER_H;
    }

    /** 屏幕给出卡的外框后排版:清掉上一版,由 {@link #layout} 从标题下面往下排。 */
    final void build(int x, int y, int w, int h, int dropBottom) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        ui.clear();
        ui.setViewportHeight(dropBottom);
        title = null;
        fields.clear();
        layout(y + DialogBox.TITLE_H);
    }

    /** 从 {@code top} 往下排这张卡的行。 */
    protected abstract void layout(int top);

    // ---- 版式件 ----

    protected void title(String text) {
        title = text;
    }

    /** 头部那张脸的左上角({@code top} 是头部的顶边)。 */
    protected int photoX() {
        return x + DialogBox.PAD_X;
    }

    protected int photoY(int top) {
        return top + (COVER_H - PHOTO) / 2;
    }

    /** 头部右半边的左缘与宽。 */
    protected int coverRight() {
        return photoX() + PHOTO + PHOTO_GAP;
    }

    protected int coverRightW() {
        return x + w - DialogBox.PAD_X - coverRight();
    }

    /** 头部右半边放一个字段时,字段的顶边(竖直居中)。 */
    protected static int coverFieldY(int top) {
        return top + (COVER_H - FIELD_H) / 2;
    }

    /** 字段:淡色标签在 {@code (fx, fy)},下划线输入框在它下面;出错时标签让位给错误。 */
    protected TextField field(int fx, int fy, int fw, String label, TextField field) {
        Label l = ui.add(new Label(label, Label.Role.SECONDARY));
        l.setBounds(fx, fy, fw, 9);
        ui.add(field.underlined(true).withLabel(l));
        field.setBounds(fx, fy + FIELD_LABEL_H, fw, FIELD_H - FIELD_LABEL_H);
        fields.add(field);
        return field;
    }

    /** 选择行:整卡宽,从 {@code ry} 起一行。 */
    protected Dropdown select(int ry, String label, List<String> items, int selected, IntConsumer onSelect) {
        Dropdown d = ui.add(new Dropdown(items, selected, onSelect).row(label, DialogBox.PAD_X - 1));
        d.setBounds(x + 1, ry, w - 2, ROW_H);   // 贴着卡的描边里面,悬停浅底铺满整行
        return d;
    }

    /** 右下一排纯字钮:取消在左、主按钮在右,各自按字宽定宽。返回主按钮。 */
    protected Button buttons(String cancelLabel, Runnable onCancel, String okLabel, Runnable onOk) {
        var font = Minecraft.getInstance().font;
        int by = DialogBox.buttonTop(y, h);
        int okW = DialogBox.buttonW(font.width(okLabel));
        int cancelW = DialogBox.buttonW(font.width(cancelLabel));
        int okX = x + w - DialogBox.BUTTON_RIGHT - okW;
        Button cancel = ui.add(new Button(cancelLabel, Button.Style.LINK, onCancel));
        cancel.setBounds(okX - DialogBox.BUTTON_GAP - cancelW, by, cancelW, DialogBox.BUTTON_H);
        Button ok = ui.add(new Button(okLabel, Button.Style.LINK, onOk));
        ok.setBounds(okX, by, okW, DialogBox.BUTTON_H);
        primary = ok;
        primaryAction = onOk;
        cancelAction = onCancel;
        return ok;
    }

    /** 头部的名字:加粗,右半边竖直居中。 */
    protected void coverName(McDrawSurface s, NumenTheme.Colors c, int top, String name) {
        bold(s.graphics(), name, coverRight(), top + (COVER_H - 8) / 2, coverRightW(), c.textPrimary());
    }

    private static void bold(GuiGraphics g, String text, int tx, int ty, int maxW, int color) {
        var font = Minecraft.getInstance().font;
        // 加粗的字更宽:按加粗后的宽截
        String fit = com.dwinovo.numen.client.ui.TextClip.fit(
                t -> font.width(Component.literal(t).withStyle(ChatFormatting.BOLD)), text, maxW);
        Nb.text(g, font, Component.literal(fit).withStyle(ChatFormatting.BOLD), tx, ty, color);
    }

    // ---- 宿主转发面 ----

    /**
     * 画卡里的东西(外框和暗幕是屏幕画的)。{@code alpha} 是卡此刻的不透明度:乘进着色器颜色,
     * 字、脸、控件一起淡。
     */
    final void render(McDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs, float alpha) {
        GuiGraphics g = s.graphics();
        g.setColor(1f, 1f, 1f, alpha);
        if (title != null) {
            bold(g, title, x + DialogBox.PAD_X, y + DialogBox.TITLE_TOP, w - DialogBox.PAD_X * 2, c.textPrimary());
        }
        paint(s, c, mouseX, mouseY, alpha);
        ui.render(s, c, mouseX, mouseY, nowMs);
        g.setColor(1f, 1f, 1f, 1f);
    }

    /** 控件之外卡自己画的东西(脸之类 MC 独有的);在控件下面。 */
    protected void paint(McDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, float alpha) {}

    /** 贴一枚图标:{@link Sprites#draw} 贴完会把着色器颜色复位,这里接着把卡的不透明度设回去。 */
    protected static void icon(GuiGraphics g, ResourceLocation sprite, int ix, int iy, int size, int argb, float alpha) {
        Sprites.draw(g, sprite, ix, iy, size, DialogBox.fade(argb, alpha));
        g.setColor(1f, 1f, 1f, alpha);
    }

    /** 悬停提示(宿主画 tooltip);没有则 null。 */
    String tooltipAt(double mx, double my) {
        return null;
    }

    boolean mouseClicked(double mx, double my, int button) {
        return ui.mouseClicked(mx, my, button);
    }

    boolean mouseScrolled(double mx, double my, double delta) {
        return ui.mouseScrolled(mx, my, delta);
    }

    /** Tab 走输入框、回车按主按钮、Esc 取消;其余键给获焦的控件。 */
    final boolean keyPressed(int keyCode, int modifiers) {
        switch (keyCode) {
            case KeyCodes.ESCAPE -> {
                if (ui.hasOverlay()) return ui.keyPressed(keyCode, modifiers);   // 先收展开的下拉
                cancelAction.run();
                return true;
            }
            case KeyCodes.TAB -> {
                if (!ui.hasOverlay()) focusStep(KeyCodes.shift(modifiers) ? -1 : 1);
                return true;   // 不给屏幕:原版的 Tab 会把焦点挪到底下的真输入框上,两边的焦点就对不上了
            }
            case KeyCodes.ENTER -> {
                if (!ui.hasOverlay() && primary.enabled()) primaryAction.run();
                return true;
            }
            default -> {
                return ui.keyPressed(keyCode, modifiers);
            }
        }
    }

    /** 焦点往前或往后挪一个输入框,到头绕回;眼下没有输入框获焦时从头(或从尾)开始。 */
    private void focusStep(int dir) {
        if (fields.isEmpty()) return;
        int at = fields.indexOf(ui.focusedWidget());
        int next = at < 0 ? (dir > 0 ? 0 : fields.size() - 1) : Math.floorMod(at + dir, fields.size());
        ui.requestFocus(fields.get(next));
    }

    boolean charTyped(char ch) {
        return ui.charTyped(ch);
    }
}
