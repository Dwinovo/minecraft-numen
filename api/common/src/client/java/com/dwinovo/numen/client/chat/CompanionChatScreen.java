package com.dwinovo.numen.client.chat;

import com.dwinovo.numen.api.Delivery;
import com.dwinovo.numen.api.NumenGateway;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.screen.chat.ChatInputBar;
import com.dwinovo.numen.client.screen.settings.HostThemeColors;
import com.dwinovo.numen.client.ui.RoundRect;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.EntityHitResult;

import java.util.EnumSet;
import java.util.UUID;

/**
 * 快捷对话:按对话键(默认 Y,入口在 {@code NumenKeys})对「当前交互
 * 对象」弹出这一条极简输入行——就一行,回车说出去立刻关屏,回复会浮
 * 在它头顶的气泡里。收件人由 {@code SelectedCompanion} 解析:准星指着
 * 谁优先谁,否则是轮盘选中的那位。屏只是输入法,不是对话窗口;历史与
 * 长文在 G 面板。准星提示见 {@code TalkHint}。
 *
 * <p>输入行与 G 面板是<b>同一条</b>({@link ChatInputBar}):斜杠补全弹层、
 * {@code /skills} 这类面板、回车先补后发,两处一模一样,不另写一套。不带麦克风键
 * ——快捷语音有自己的按住说话键。命令跑完的回话走准星提示层闪一下,屏照关。
 *
 * <p>与 {@code @名字} 路由是同一条管线({@link NumenGateway#enqueue}),
 * 对应两种社交距离:@ 是远程喊话,这里是走到跟前说话。
 */
public class CompanionChatScreen extends Screen {

    private static final int INPUT_W = 300;
    /** 与 G 面板输入行同高。 */
    private static final int INPUT_H = 18;

    private final UUID companionUuid;
    private final String companionName;
    private ChatInputBar inputBar;

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

    private EntityAgentLoop loop() {
        return AgentLoopRegistry.getOrCreate(companionUuid);
    }

    /** 名字牌(说给谁)的宽度:它和输入行同排,占掉输入卡最左边这一截。 */
    private int tagW() {
        return this.font.width(companionName) + 12;
    }

    @Override
    protected void init() {
        String kept = inputBar != null ? inputBar.text() : "";
        int x = (this.width - INPUT_W) / 2;
        int y = this.height - 44;
        inputBar = new ChatInputBar(new BarHost(), EnumSet.of(ChatInputBar.Key.SEND, ChatInputBar.Key.STOP));
        int lead = tagW() + 6;
        inputBar.build(x + lead, y, INPUT_W - lead, INPUT_H);
        if (!kept.isEmpty()) inputBar.setText(kept);
    }

    /** 输入行的宿主:说话走 Gateway 然后关屏;命令的回话闪在准星提示层,屏也关。 */
    private final class BarHost implements ChatInputBar.Host {
        @Override public void onSend(String text) {
            Delivery sent = NumenGateway.enqueue(companionUuid, text);
            if (sent != Delivery.REJECTED) {
                ChatLines.owner(companionName, text, false);
            } else {
                com.dwinovo.numen.client.hud.TalkHint.flash(companionName + " 没能收到——它可能不在线", 3000);
            }
            onClose();
        }

        @Override public void onAbort() { loop().abort(); }

        @Override public boolean canAbort() { return loop().canInterrupt(); }

        @Override public String hint() {
            return "想说什么…(回车说出去,Esc 算了)";
        }

        @Override public EntityAgentLoop loop() { return CompanionChatScreen.this.loop(); }

        /** 只注册事件,不进 renderables——画面归 NumenUI。见 {@code McTextInput}。 */
        @Override public void mountInput(net.minecraft.client.gui.components.AbstractWidget w) {
            CompanionChatScreen.this.addWidget(w);
        }

        @Override public void focusInput(net.minecraft.client.gui.components.AbstractWidget w) {
            CompanionChatScreen.this.setFocused(w);
        }

        @Override public void onCommandReply(String reply) {
            if (reply == null || reply.isBlank()) {
                return;   // 不吭声的命令,或面板已在输入行原位打开——屏留着
            }
            // 行数越多给的时间越长——一屏技能清单三秒看不完
            long life = 4000L + reply.split("\n").length * 600L;
            com.dwinovo.numen.client.hud.TalkHint.flash(reply, life);
            onClose();
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        // 刻意留空:super.render 默认会画菜单模糊+压暗遮罩,面对面说话
        // 不该把世界糊掉——她就站在你面前
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        UiTheme th = UiTheme.current();
        int x = (this.width - INPUT_W) / 2;
        int y = this.height - 44;

        // 输入卡:与 G 面板同方言的浅底粗边卡片;输入行(含弹层/面板)画在它上面
        RoundRect.card(g, x - 8, y - 6, x + INPUT_W + 8, y + INPUT_H + 4, 4,
                th.aiFill(), th.border());
        // 名字牌:与输入行同排、占卡片最左一截,标明这句话说给谁。不放输入框上方——
        // 斜杠补全弹层和 /skills 面板都贴着输入框往上长,上面那块地是它们的
        int tagW = tagW();
        RoundRect.card(g, x - 4, y + 1, x - 4 + tagW, y + INPUT_H - 1, 3, th.band(), th.border());
        Nb.text(g, this.font, companionName, x + 2, y + (INPUT_H - this.font.lineHeight) / 2 + 1,
                th.onBand());
        inputBar.render(g, mouseX, mouseY, net.minecraft.Util.getMillis(), HostThemeColors.current());

        String tip = inputBar.tooltipAt(mouseX, mouseY);
        if (tip != null) {
            g.renderTooltip(this.font, Component.literal(tip), mouseX, mouseY);
        }
        super.render(g, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 输入行先拿:弹层的上下/Tab/回车、面板的整个键盘、回车发送。它不要的(比如
        // 没开面板时的 Esc)才落到屏幕——Esc 关屏
        if (inputBar != null && inputBar.keyPressed(keyCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char ch, int modifiers) {
        if (inputBar != null && inputBar.charTyped(ch)) {
            return true;
        }
        return super.charTyped(ch, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (inputBar != null && inputBar.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
