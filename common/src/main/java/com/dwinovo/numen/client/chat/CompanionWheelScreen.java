package com.dwinovo.numen.client.chat;

import com.dwinovo.numen.client.agent.KnownSkins;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.Anim;
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
 *
 * <p>动效与 GUI 同一套语汇:开盘从圆心 easeOutCubic 弹出,选中/落选的
 * 尺寸用帧率无关的指数趋近({@link Anim#approach})柔滑过渡,呼吸是
 * 浮点正弦叠在 pose 缩放上——没有任何整数跳格。
 */
public class CompanionWheelScreen extends Screen {

    private static final int AVATAR = 26;             // 基础头像尺寸(px)
    private static final float SELECTED_PX = 7f;      // 选中态的目标增量(px)
    private static final float APPROACH_RATE = 16f;   // 尺寸趋近速率(与聊天滚动同族)
    private static final float BREATH_AMP = 0.035f;   // 呼吸振幅(选中态缩放比)
    private static final long BREATH_PERIOD_MS = 2600;
    private static final long OPEN_MS = 170;          // 开盘弹出时长
    private static final int DEAD_ZONE = 18;          // 圆心附近不改选中

    private final List<NumenRoster.Entry> entries;
    private final float[] sizePx;                     // 每席位的动画中尺寸
    private final long openedAtMs = System.currentTimeMillis();
    private long lastFrameNanos = System.nanoTime();
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
        // 初始就带着选中差,开盘瞬间不闪变
        this.sizePx = new float[entries.size()];
        for (int i = 0; i < sizePx.length; i++) {
            sizePx[i] = i == index ? AVATAR + SELECTED_PX : AVATAR;
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
        long nowMs = System.currentTimeMillis();
        long nowNanos = System.nanoTime();
        float dt = Math.min((nowNanos - lastFrameNanos) / 1.0e9f, 0.1f);
        lastFrameNanos = nowNanos;
        // 开盘进度:圆环半径与头像尺寸一起从圆心弹出,快起柔收
        float open = Anim.easeOutCubic((nowMs - openedAtMs) / (float) OPEN_MS);

        // 背景轻压随开盘淡入,聚焦轮盘;不模糊——世界还在那儿
        g.fill(0, 0, this.width, this.height, (int) (0x48 * open) << 24);

        int cx = this.width / 2;
        int cy = this.height / 2;
        int r = radius();
        float rNow = r * open;

        // 鼠标指向选中:圆心死区外,按方位角就近吸附
        double dx = mouseX - cx;
        double dy = mouseY - cy;
        if (dx * dx + dy * dy > (double) DEAD_ZONE * DEAD_ZONE) {
            index = nearestSlot(Math.atan2(dy, dx));
        }

        UiTheme th = UiTheme.current();
        for (int i = 0; i < entries.size(); i++) {
            boolean sel = i == index;
            // 选中/落选:指数趋近目标尺寸——快速跟手,末端自然缓住
            sizePx[i] = Anim.approach(sizePx[i], sel ? AVATAR + SELECTED_PX : AVATAR,
                    APPROACH_RATE, dt);

            double ang = slotAngle(i);
            float ax = cx + (float) (Math.cos(ang) * rNow);
            float ay = cy + (float) (Math.sin(ang) * rNow);

            // 呼吸:慢周期浮点正弦,只叠在选中者的缩放上
            float breath = sel
                    ? 1f + BREATH_AMP * (0.5f + 0.5f * (float) Math.sin(
                            nowMs % BREATH_PERIOD_MS / (double) BREATH_PERIOD_MS * Math.PI * 2))
                    : 1f;
            float scale = sizePx[i] / AVATAR * breath * open;

            g.pose().pushPose();
            g.pose().translate(ax, ay, 0);
            g.pose().scale(scale, scale, 1f);
            int half = AVATAR / 2;
            RoundRect.fill(g, -half - 2, -half - 2, half + 2, half + 2, 4,
                    sel ? th.cta() : th.border());
            PlayerFaceRenderer.draw(g, KnownSkins.of(entries.get(i).uuid()),
                    -half, -half, AVATAR);
            g.pose().popPose();
        }

        // 圈顶名牌:当前选中 xxx(随开盘一起立起)
        if (open > 0.4f) {
            String label = "当前选中  " + entries.get(index).name();
            int tw = this.font.width(label);
            int nx = cx - tw / 2;
            int ny = cy - r - 40;
            RoundRect.card(g, nx - 10, ny - 6, nx + tw + 10, ny + 14, 4, th.aiFill(), th.border());
            Nb.text(g, this.font, label, nx, ny, th.text());

            String hint = "滚轮/指向选择 · 点击或松开确认 · Esc 取消";
            Nb.text(g, this.font, hint, cx - this.font.width(hint) / 2, cy + r + 30, 0xB0FFFFFF);
        }
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
