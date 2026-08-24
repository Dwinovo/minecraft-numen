package com.dwinovo.numen.client.chat;

import com.dwinovo.numen.client.NumenKeys;
import com.dwinovo.numen.client.agent.KnownSkins;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.hud.TalkHint;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.Anim;
import com.dwinovo.numen.client.ui.RoundRect;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 同伴转盘:抽奖转盘的操作模型——顶槽固定(金环 + 指针 ▼),滚轮转动
 * 整个轮盘把人送进顶槽,点击别处的头像沿最短路径转过去,点击顶槽头像
 * 确认,松开轮盘键也确认(对讲机手感),Esc 放弃不改选。悬浮任意头像
 * 在光标旁浮名字;确认关盘后准星下方闪一行「按 [键] 对话 · 按住 [键]
 * 说话」教学下一步。
 *
 * <p>动效:旋转角走欠阻尼弹簧(拨过去带一丝回弹,拨盘手感),顶槽
 * 住客的尺寸用帧率无关指数趋近柔滑放大,呼吸是慢周期浮点正弦叠在
 * pose 缩放上;开盘 170ms 从圆心 easeOutCubic 弹出。
 */
public class CompanionWheelScreen extends Screen {

    private static final int AVATAR = 26;             // 基础头像尺寸(px)
    private static final float SELECTED_PX = 7f;      // 顶槽住客的目标增量(px)
    private static final float APPROACH_RATE = 16f;   // 尺寸趋近速率
    private static final float BREATH_AMP = 0.035f;   // 呼吸振幅(缩放比)
    private static final long BREATH_PERIOD_MS = 2600;
    private static final long OPEN_MS = 170;          // 开盘弹出时长
    private static final float SPRING_K = 260f;       // 旋转弹簧刚度
    private static final float SPRING_DAMP = 0.78f;   // 阻尼比(<1:一丝回弹)
    private static final long FLASH_MS = 3200;        // 关盘教学提示时长

    private final List<NumenRoster.Entry> entries;
    private final float[] sizePx;
    private final long openedAtMs = System.currentTimeMillis();
    private long lastFrameNanos = System.nanoTime();

    /** 顶槽目标(entries 下标);旋转角朝它的席位角趋近。 */
    private int index;
    /** 轮盘当前旋转角(度)与角速度——欠阻尼弹簧驱动。 */
    private float rotDeg;
    private float rotVel;

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
        this.rotDeg = targetRotFor(index);   // 开盘即对位,不空转
        this.sizePx = new float[entries.size()];
        for (int i = 0; i < sizePx.length; i++) {
            sizePx[i] = i == index ? AVATAR + SELECTED_PX : AVATAR;
        }
    }

    private static boolean physicallyDown(long window, KeyMapping k) {
        if (k.isUnbound()) {
            return false;
        }
        InputConstants.Key key = InputConstants.getKey(k.saveString());
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        }
        return InputConstants.isKeyDown(window, key.getValue());
    }

    /**
     * 移动放行的唯一通路(由 {@code MixinKeyboardInput} 在原版采样之后
     * 调用,两个加载器同一个点):开屏期间按键系统被闸,采出来的移动
     * 意图全是零——这里按 GLFW 物理按键状态原样重建,奔跑不为选人断步,
     * 开盘前就按住的 W 也无缝接上。不碰 {@code KeyMapping} 状态,键位
     * 冲突类模组无感。鼠标视角仍锁定(被转盘征用),武器轮盘式取舍。
     *
     * <p>1.21.2+:按键意图搬进不可变的 {@code Input} 记录,整条重建而非逐字段赋值;
     * 潜行减速由 {@code LocalPlayer.aiStep} 在 {@code input.tick()} 之后自己乘,
     * 这里不再重复施加。疾跑位沿用原版本 tick 采到的值——与旧代一致(疾跑键本
     * 就随开屏失活,靠的是移动意图不断档来保住已在跑的状态)。
     */
    public static void feedMovement(net.minecraft.client.player.ClientInput input) {
        Minecraft mc = Minecraft.getInstance();
        long window = mc.getWindow().getWindow();
        boolean up = physicallyDown(window, mc.options.keyUp);
        boolean down = physicallyDown(window, mc.options.keyDown);
        boolean left = physicallyDown(window, mc.options.keyLeft);
        boolean right = physicallyDown(window, mc.options.keyRight);
        boolean jump = physicallyDown(window, mc.options.keyJump);
        boolean shift = physicallyDown(window, mc.options.keyShift);
        input.keyPresses = new net.minecraft.world.entity.player.Input(
                up, down, left, right, jump, shift, input.keyPresses.sprint());
        input.forwardImpulse = (up ? 1f : 0f) - (down ? 1f : 0f);
        input.leftImpulse = (left ? 1f : 0f) - (right ? 1f : 0f);
    }

    private float step() {
        return 360f / entries.size();
    }

    /** 让 i 号坐进顶槽所需的旋转角。 */
    private float targetRotFor(int i) {
        return -i * step();
    }

    private int radius() {
        return Math.clamp(Math.min(this.width, this.height) / 5, 56, 104);
    }

    /** i 号此刻的方位角(弧度,顶槽为 -90°)。 */
    private double slotAngle(int i) {
        return Math.toRadians(-90 + i * step() + rotDeg);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        // 崩溃护栏:轮盘渲染出错直接关盘,不带走游戏
        if (!com.dwinovo.numen.client.ui.SafeUi.run("wheel-render",
                () -> renderInner(g, mouseX, mouseY))) {
            onClose();
        }
    }

    private void renderInner(GuiGraphics g, int mouseX, int mouseY) {
        if (entries.isEmpty()) {
            onClose();
            return;
        }
        long nowMs = System.currentTimeMillis();
        long nowNanos = System.nanoTime();
        float dt = Math.min((nowNanos - lastFrameNanos) / 1.0e9f, 0.05f);
        lastFrameNanos = nowNanos;
        float open = Anim.easeOutCubic((nowMs - openedAtMs) / (float) OPEN_MS);

        // 旋转角:欠阻尼弹簧追目标——拨过去,末端一丝回弹
        float target = rotDeg + wrapDeg(targetRotFor(index) - rotDeg);
        float acc = SPRING_K * (target - rotDeg)
                - 2f * (float) Math.sqrt(SPRING_K) * SPRING_DAMP * rotVel;
        rotVel += acc * dt;
        rotDeg += rotVel * dt;
        if (Math.abs(target - rotDeg) < 0.05f && Math.abs(rotVel) < 0.5f) {
            rotDeg = target;
            rotVel = 0f;
        }

        g.fill(0, 0, this.width, this.height, (int) (0x48 * open) << 24);

        int cx = this.width / 2;
        int cy = this.height / 2;
        int r = radius();
        float rNow = r * open;
        UiTheme th = UiTheme.current();

        // 顶槽底座:固定的金环 + 指针 ▼(先画,头像转进来压在上面)
        int topX = cx;
        int topY = cy - Math.round(rNow);
        int ringHalf = AVATAR / 2 + 5;
        RoundRect.fill(g, topX - ringHalf, topY - ringHalf, topX + ringHalf, topY + ringHalf, 5,
                th.cta());
        Nb.text(g, this.font, "▼", topX - this.font.width("▼") / 2,
                topY - ringHalf - 12, th.cta());

        int hovered = -1;
        for (int i = 0; i < entries.size(); i++) {
            boolean atTop = i == index;
            sizePx[i] = Anim.approach(sizePx[i], atTop ? AVATAR + SELECTED_PX : AVATAR,
                    APPROACH_RATE, dt);

            double ang = slotAngle(i);
            float ax = cx + (float) (Math.cos(ang) * rNow);
            float ay = cy + (float) (Math.sin(ang) * rNow);
            if (hitAvatar(mouseX, mouseY, ax, ay)) {
                hovered = i;
            }

            float breath = atTop
                    ? 1f + BREATH_AMP * (0.5f + 0.5f * (float) Math.sin(
                            nowMs % BREATH_PERIOD_MS / (double) BREATH_PERIOD_MS * Math.PI * 2))
                    : 1f;
            float scale = sizePx[i] / AVATAR * breath * open;

            g.pose().pushPose();
            g.pose().translate(ax, ay, 0);
            g.pose().scale(scale, scale, 1f);
            int half = AVATAR / 2;
            if (!atTop) {
                RoundRect.fill(g, -half - 2, -half - 2, half + 2, half + 2, 4, th.border());
            }
            PlayerFaceRenderer.draw(g, KnownSkins.of(entries.get(i).uuid()),
                    -half, -half, AVATAR);
            g.pose().popPose();
        }

        if (open > 0.4f) {
            // 顶槽名牌:当前选中 xxx
            String label = "当前选中  " + entries.get(index).name();
            int tw = this.font.width(label);
            int nx = cx - tw / 2;
            int ny = cy - r - 46;
            RoundRect.card(g, nx - 10, ny - 6, nx + tw + 10, ny + 14, 4, th.aiFill(), th.border());
            Nb.text(g, this.font, label, nx, ny, th.text());

            String hint = "滚轮转盘 · 点击送到顶槽 · 点顶槽或松开确认 · Esc 取消";
            Nb.text(g, this.font, hint, cx - this.font.width(hint) / 2, cy + r + 30, 0xB0FFFFFF);
        }

        // 悬浮名字:光标旁小字(顶槽住客的名字已在名牌上,不重复)
        if (hovered >= 0 && hovered != index) {
            String name = entries.get(hovered).name();
            Nb.text(g, this.font, name, mouseX + 10, mouseY - 4, 0xE0FFFFFF);
        }
    }

    private boolean hitAvatar(double mx, double my, float ax, float ay) {
        int half = AVATAR / 2 + 3;
        return mx >= ax - half && mx <= ax + half && my >= ay - half && my <= ay + half;
    }

    private static float wrapDeg(float a) {
        while (a > 180f) a -= 360f;
        while (a < -180f) a += 360f;
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
        if (button != 0 || entries.isEmpty()) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        // 命中检测与渲染同一套席位坐标
        int cx = this.width / 2;
        int cy = this.height / 2;
        float rNow = radius() * Anim.easeOutCubic(
                (System.currentTimeMillis() - openedAtMs) / (float) OPEN_MS);
        for (int i = 0; i < entries.size(); i++) {
            double ang = slotAngle(i);
            float ax = cx + (float) (Math.cos(ang) * rNow);
            float ay = cy + (float) (Math.sin(ang) * rNow);
            if (hitAvatar(mouseX, mouseY, ax, ay)) {
                if (i == index) {
                    confirm();      // 点顶槽住客:确认关盘
                } else {
                    index = i;      // 点别处:最短路径转过去(弹簧自己追)
                }
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        // 按住开、松开定:轮盘键抬起即确认(对讲机手感)
        if (NumenKeys.COMPANION_WHEEL.matches(keyCode, scanCode) && !entries.isEmpty()) {
            confirm();
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    private void confirm() {
        NumenRoster.Entry chosen = entries.get(index);
        SelectedCompanion.set(chosen.uuid());
        // 关盘教学:下一步怎么跟它说话
        TalkHint.flash("已选中 " + chosen.name()
                + " · 按 [" + NumenKeys.TALK_COMPANION.getTranslatedKeyMessage().getString()
                + "] 对话 · 按住 [" + NumenKeys.QUICK_VOICE.getTranslatedKeyMessage().getString()
                + "] 说话", FLASH_MS);
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
