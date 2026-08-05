package com.dwinovo.numen.client.chat;

import com.dwinovo.numen.api.NumenGateway;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.screen.FlatEditBox;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.RoundRect;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.EntityHitResult;

import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/**
 * 快捷对话:按对话键(默认 Y,入口在 {@code NumenKeys})对「当前交互
 * 对象」弹出这一条极简输入框——就一行字,回车说出去立刻关屏,回复会浮
 * 在它头顶的气泡里。收件人由 {@code SelectedCompanion} 解析:准星指着
 * 谁优先谁,否则是轮盘选中的那位。屏只是输入法,不是对话窗口;历史与
 * 长文在 G 面板。准星提示见 {@code TalkHint}。
 *
 * <p>与 {@code @名字} 路由是同一条管线({@link NumenGateway#enqueue}),
 * 对应两种社交距离:@ 是远程喊话,这里是走到跟前说话。
 */
public class CompanionChatScreen extends Screen {

    private static final int INPUT_W = 300;
    private static final int INPUT_H = 14;

    private final UUID companionUuid;
    private final String companionName;
    private FlatEditBox input;

    public CompanionChatScreen(UUID companionUuid, String companionName) {
        super(Component.literal("Numen face-to-face chat"));
        this.companionUuid = companionUuid;
        this.companionName = companionName == null ? "?" : companionName;
    }

    /**
     * 准星此刻指着的「自己的」同伴,不在指着返回 null。花名册只含本人的
     * 同伴,身份校验是白送的;别人的同伴不响应(它的大脑不在你机器上)。
     */
    public static AbstractClientPlayer crosshairCompanion() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.isSpectator()) return null;
        if (!(mc.hitResult instanceof EntityHitResult hit)) return null;
        if (!(hit.getEntity() instanceof AbstractClientPlayer body)) return null;
        for (NumenRoster.Entry entry : NumenRoster.instance().entries()) {
            if (entry.uuid().equals(body.getUUID())) {
                return body;
            }
        }
        return null;
    }

    @Override
    protected void init() {
        String kept = input != null ? input.getValue() : "";
        int x = (this.width - INPUT_W) / 2;
        int y = this.height - 44;
        // G 面板同款 FlatEditBox:深色字、无阴影、占位提示画在光标下层——
        // 原版 EditBox 的阴影是写死的,浅底上深色字会糊成一团
        input = new FlatEditBox(this.font, x, y, INPUT_W, INPUT_H,
                Component.literal("numen chat input"));
        input.setBordered(false);
        input.setTextColor(UiTheme.current().text());
        input.setHint(Nb.colored("想说什么…(回车说出去,Esc 算了)", UiTheme.current().textDim()));
        input.setMaxLength(256);
        input.setValue(kept);
        input.setCanLoseFocus(false);
        addWidget(input);
        setInitialFocus(input);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        // 刻意留空:super.render 默认会画菜单模糊+压暗遮罩,面对面说话
        // 不该把世界糊掉——她就站在你面前
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        UiTheme th = UiTheme.current();
        int x = input.getX();
        int y = input.getY();

        // 名字牌:输入框左上角一枚小卡,标明这句话说给谁
        String tag = companionName;
        int tagW = this.font.width(tag) + 12;
        RoundRect.card(g, x - 6, y - 24, x - 6 + tagW, y - 9, 3, th.band(), th.border());
        Nb.text(g, this.font, tag, x, y - 20, th.onBand());

        // 输入卡:与 G 面板同方言的浅底粗边卡片
        RoundRect.card(g, x - 8, y - 6, x + INPUT_W + 8, y + INPUT_H + 4, 4,
                th.aiFill(), th.border());
        input.render(g, mouseX, mouseY, partialTicks);

        super.render(g, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            send();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void send() {
        String text = input.getValue().trim();
        if (!text.isEmpty()) {
            NumenGateway.Delivery sent = NumenGateway.enqueue(companionUuid, text);
            if (sent != NumenGateway.Delivery.REJECTED) {
                ChatLines.owner(companionName, text, false,
                    sent == NumenGateway.Delivery.QUEUED);
            } else {
                com.dwinovo.numen.client.hud.TalkHint.flash(
                        companionName + " 没能收到——它可能不在线", 3000);
            }
        }
        onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
