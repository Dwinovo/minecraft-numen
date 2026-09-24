package com.dwinovo.numen.client.chat;

import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.api.Delivery;
import com.dwinovo.numen.api.NumenGateway;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.Conversations;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.screen.chat.ChatInputBar;
import com.dwinovo.numen.client.screen.settings.HostThemeColors;
import com.dwinovo.numen.client.skin.ConversationFaces;

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
 * 在它头顶的气泡里。收件人是一个会话,由 {@code SelectedCompanion} 解析:准星指着
 * 谁就是跟她一个人说,否则是转盘选中的那个(可能是多人会话)。屏只是输入法,不是对话窗口;
 * 历史与长文在 G 面板。准星提示见 {@code TalkHint}。
 *
 * <p>输入行与 G 面板是<b>同一条</b>({@link ChatInputBar}):斜杠补全弹层、
 * {@code /skills} 这类面板、回车先补后发,两处一模一样,不另写一套。不带麦克风键
 * ——快捷语音有自己的按住说话键。命令跑完的回话走准星提示层闪一下,屏照关。
 *
 * <p>与 {@code @名字} 路由是同一条管线({@link NumenGateway#emit}),
 * 对应两种社交距离:@ 是远程喊话,这里是走到跟前说话。
 *
 * <p>样子照 Telegram 的输入区:窗口底色的一条、浮起的细描边;左端是说给谁——会话头像(群是群头像)加名字,
 * 一道分隔线隔开输入框。开屏时整条自下浮起 {@link #OPEN_MS},不硬切。
 *
 * <p>有征询挂着时按对话键先答征询(对象是最早在等的那位,见 {@code NumenKeys}):输入行被答复框取代,答完
 * 还有别的同伴在等就换到她,都答完且输入框是空的就关屏——和说完一句一样。
 */
public class CompanionChatScreen extends Screen {

    private static final int INPUT_W = 300;
    /** 与 G 面板输入行同高。 */
    private static final int INPUT_H = 18;
    /** 名字牌里的会话头像:输入行里上下各留 2。 */
    private static final int FACE = INPUT_H - 4;
    /** 开屏浮起:Telegram 的快动效时长,浮起的高度。 */
    private static final long OPEN_MS = 150;
    private static final int OPEN_RISE = 8;

    private final Conversation conv;
    private final String companionName;
    private ChatInputBar inputBar;
    private final long openedAtMs = net.minecraft.Util.getMillis();

    public CompanionChatScreen(Conversation conv) {
        super(Component.literal("Numen face-to-face chat"));
        this.conv = conv;
        this.companionName = conv.displayName(NumenRoster.instance()::name);
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

    /** 就他俩那只的大脑(补全、征询答复框);会话没有单一的主时 null。 */
    private EntityAgentLoop loop() {
        UUID her = Conversations.instance().soloOf(conv);
        return her == null ? null : AgentLoopRegistry.getOrCreate(her);
    }

    /** 名字牌(说给谁:头像 + 名字)的宽度:它和输入行同排,占掉输入卡最左边这一截。 */
    private int tagW() {
        return FACE + 4 + this.font.width(companionName) + 4;
    }

    @Override
    protected void init() {
        // 输入框的真控件挂到本屏上:只注册事件、不进 renderables,画面归 NumenUI。
        // 屏幕作用域——关屏时在 removed() 卸掉。
        com.dwinovo.numen.client.ui.mc.McTextInput.mountVia(this::addWidget, this::setFocused);
        String kept = inputBar != null ? inputBar.text() : "";
        int x = (this.width - INPUT_W) / 2;
        int y = this.height - 44;
        inputBar = new ChatInputBar(new BarHost(), EnumSet.of(ChatInputBar.Key.SEND, ChatInputBar.Key.STOP));
        int lead = tagW() + 6;
        inputBar.build(x, y, INPUT_W, INPUT_H, lead);
        if (!kept.isEmpty()) inputBar.setText(kept);
    }

    /** 输入行的宿主:说话走 Gateway 然后关屏;命令的回话闪在准星提示层,屏也关。 */
    private final class BarHost implements ChatInputBar.Host {
        @Override public void onSend(String text) {
            if (Conversations.instance().say(conv, text).reached()) {
                ChatLines.owner(companionName, text, false);
            } else {
                com.dwinovo.numen.client.hud.TalkHint.flash(companionName + " 没能收到——它可能不在线", 3000);
            }
            onClose();
        }

        @Override public void onAbort() { Conversations.instance().abort(conv); }   // 停止停全体

        @Override public boolean canAbort() { return Conversations.instance().canAbort(conv); }

        @Override public String hint() {
            return "想说什么…(回车说出去,Esc 算了)";
        }

        @Override public EntityAgentLoop loop() { return CompanionChatScreen.this.loop(); }

        @Override public Conversation conversation() { return conv; }

        @Override public void onConsentSettled() {
            var next = com.dwinovo.numen.client.consent.ConsentCards.first();
            if (next != null) {
                minecraft.setScreen(new CompanionChatScreen(Conversations.instance().of(next.companion())));
            } else if (inputBar.text().isEmpty()) {
                onClose();
            }
        }

        /** 只注册事件,不进 renderables——画面归 NumenUI。见 {@code McTextInput}。 */
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
        long now = net.minecraft.Util.getMillis();

        // 开屏浮起:只挪画面,点击区不跟着挪——150ms 里差几个像素,点不偏
        float rise = 1f - com.dwinovo.numen.client.ui.Anim.easeOutCubic((now - openedAtMs) / (float) OPEN_MS);
        g.pose().pushPose();
        g.pose().translate(0, Math.round(OPEN_RISE * rise), 0);
        // 答复框在场时整行归它(它自己写着谁在问),输入卡与名字牌都不画
        if (!inputBar.asking()) {
            var surface = new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, this.font);
            // 输入卡:Telegram 输入区是窗口底色,浮在世界上用浮起的描边;输入行(含弹层/面板)画在它上面
            com.dwinovo.numen.client.ui.NumenStyle.box(surface, x - 8, y - 6, INPUT_W + 16, INPUT_H + 10,
                    th.band(), th.aiBorder());
            // 名字牌:与输入行同排、占卡片最左一截,标明这句话说给谁。不放输入框上方——
            // 斜杠补全弹层和 /skills 面板都贴着输入框往上长,上面那块地是它们的
            ConversationFaces.draw(g, conv, x, y + (INPUT_H - FACE) / 2, FACE);
            Nb.text(g, this.font, companionName, x + FACE + 4, y + (INPUT_H - this.font.lineHeight) / 2 + 1,
                    th.onBand());
            int div = x + tagW() + 2;
            g.fill(div, y + 1, div + 1, y + INPUT_H - 1, th.surfaceBorder());
        }
        inputBar.render(g, mouseX, mouseY, now, HostThemeColors.current());
        g.pose().popPose();

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
    public void tick() {
        if (inputBar != null) {
            inputBar.tick();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        com.dwinovo.numen.client.ui.mc.McTextInput.mountVia(null, null);
        super.removed();
    }
}
