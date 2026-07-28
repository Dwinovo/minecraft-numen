package com.dwinovo.numen.client.chat;

import com.dwinovo.numen.client.agent.KnownSkins;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.RoundRect;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 同伴轮盘:头像围一圈,滚轮轮转、鼠标指向、点击都能选,选中者放大带
 * 呼吸,圈顶浮「当前选中 名字」。按住轮盘键打开、松开即确认(对讲机
 * 手感);点击立即确认;Esc 放弃不改选。选中结果写进
 * {@link SelectedCompanion},此后快捷对话/快捷语音都发给这一位。
 */
public class CompanionWheelScreen extends Screen {

    private static final int AVATAR = 26;
    private static final int SELECTED_BONUS = 5;      // 选中基础加号
    private static final float BREATH_AMP = 1.6f;     // 呼吸振幅(px)
    private static final long BREATH_PERIOD_MS = 1100;
    private static final int DEAD_ZONE = 18;          // 圆心附近不改选中

    private final List<NumenRoster.Entry> entries;
    private int index;

    public CompanionWheelScreen() {
        super(Component.literal("Numen companion wheel"));
        this.entries = NumenRoster.instance().entries();
        this.index = 0;
        var current = SelectedCompanion.get();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).uuid().equals(current)) {
                this.index = i;
                break;
            }
        }
    }

    private int radius() {
        return Math.clamp(Math.min(this.width, this.height) / 5, 56, 104);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        if (entries.isEmpty()) {
            onClose();
            return;
        }
        // 轻压一层缓和背景,聚焦轮盘;不模糊——世界还在那儿
        g.fill(0, 0, this.width, this.height, 0x48000000);

        int cx = this.width / 2;
        int cy = this.height / 2;
        int r = radius();

        // 鼠标指向选中:圆心死区外,按方位角就近吸附
        double dx = mouseX - cx;
        double dy = mouseY - cy;
        if (dx * dx + dy * dy > (double) DEAD_ZONE * DEAD_ZONE) {
            index = nearestSlot(Math.atan2(dy, dx));
        }

        UiTheme th = UiTheme.current();
        long now = System.currentTimeMillis();
        for (int i = 0; i < entries.size(); i++) {
            double ang = slotAngle(i);
            int ax = cx + (int) Math.round(Math.cos(ang) * r);
            int ay = cy + (int) Math.round(Math.sin(ang) * r);
            boolean sel = i == index;
            int size = AVATAR;
            if (sel) {
                // 呼吸:选中者大一点点,还在轻轻起伏
                float breath = (float) Math.sin(now % BREATH_PERIOD_MS
                        / (double) BREATH_PERIOD_MS * Math.PI * 2);
                size = AVATAR + SELECTED_BONUS + Math.round(BREATH_AMP * (0.5f + 0.5f * breath));
            }
            int half = size / 2;
            // 选中金环、未选中深框——与 G 面板同语汇
            RoundRect.fill(g, ax - half - 2, ay - half - 2, ax + half + 2, ay + half + 2, 4,
                    sel ? th.cta() : th.border());
            PlayerFaceRenderer.draw(g, KnownSkins.of(entries.get(i).uuid()),
                    ax - half, ay - half, size);
        }

        // 圈顶名牌:当前选中 xxx
        String label = "当前选中  " + entries.get(index).name();
        int tw = this.font.width(label);
        int nx = cx - tw / 2;
        int ny = cy - r - 40;
        RoundRect.card(g, nx - 10, ny - 6, nx + tw + 10, ny + 14, 4, th.aiFill(), th.border());
        Nb.text(g, this.font, label, nx, ny, th.text());

        // 脚注:操作提示,淡淡一行
        String hint = "滚轮/指向选择 · 点击或松开确认 · Esc 取消";
        Nb.text(g, this.font, hint, cx - this.font.width(hint) / 2, cy + r + 30, 0xB0FFFFFF);
    }

    private double slotAngle(int i) {
        return Math.toRadians(-90 + i * 360.0 / entries.size());
    }

    private int nearestSlot(double mouseAngle) {
        int best = index;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            double diff = Math.abs(wrapAngle(mouseAngle - slotAngle(i)));
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    private static double wrapAngle(double a) {
        while (a > Math.PI) a -= Math.PI * 2;
        while (a < -Math.PI) a += Math.PI * 2;
        return a;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!entries.isEmpty()) {
            int n = entries.size();
            index = ((index + (scrollY < 0 ? 1 : -1)) % n + n) % n;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !entries.isEmpty()) {
            confirm();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        // 按住开、松开定:轮盘键抬起即确认(对讲机手感)
        if (com.dwinovo.numen.client.NumenKeys.COMPANION_WHEEL.matches(keyCode, scanCode)
                && !entries.isEmpty()) {
            confirm();
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    private void confirm() {
        SelectedCompanion.set(entries.get(index).uuid());
        onClose();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        // 刻意留空:不要菜单模糊(render 里自画一层轻压暗)
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
