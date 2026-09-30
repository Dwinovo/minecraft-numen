package com.dwinovo.numen.entity;

import com.dwinovo.numen.event.NumenEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;

import java.util.function.Consumer;

/**
 * 客户端那一半。这具身体没有客户端,凡是服务端"发出去、然后等对面回话"的握手都会永远悬着;
 * 这里代替客户端把回话送回去。服务端对她说的话(系统聊天与动作栏)真客户端会显示给玩家看,这里交给模型。
 *
 * <h2>边界:只回握手、只传话,不报物理</h2>
 * 这里只认两类下行包:<b>服务端会等回执</b>的握手,和<b>服务端对她说的话</b>。走路、视角这些"客户端本地算、
 * 再上报给服务端"的东西不归这儿——那是 {@link NumenPlayer#tick()} 里自己跑的物理:服务端算出来就是结果,
 * 没有谁在等一个回复。所以这个类不会长成一个客户端实现,它的面就是原版那几处握手加上聊天栏与动作栏。
 *
 * <h2>对她说的话,各有一个出处</h2>
 * <ul>
 *   <li>她自己执行的指令说的话(指令回给来源的、指令直接对她说的)是那条指令的回执,执行期间由
 *       {@link #runCommand} 收给那条指令,不再当事件交;</li>
 *   <li>她自己的死亡广播就是死亡事件里那句死因({@link NumenPlayer#deathMessage}),不再交一遍;</li>
 *   <li>玩家聊天是另一种包,群聊那一路管,这里不碰;</li>
 *   <li>其余的交成 {@code server_message} 事件:聊天栏同一句刷屏折叠,动作栏当一格、最新的为准,见
 *       {@link ServerMessages}。这种事件捎带投递,不单独叫醒她。</li>
 * </ul>
 * 文字在服务端按服务端的语言表拼出来,和指令回执({@code Echo})用的是同一个拼法。
 *
 * <p>反过来说,原版那趟 {@code ServerGamePacketListenerImpl.tick()} 也不能直接拿来跑:
 * 它是 {@code resetPosition()} 记下当前位置 → {@code doTick()} → {@code absMoveTo(firstGood)},
 * 为"客户端权威的身体"写的——真玩家的 {@code doTick()} 不推动身体,最后那一下是在确认他
 * 还在客户端说的位置上。而我们的身体恰恰靠 {@code doTick()} 里的 {@code travel()} 走路,
 * 跑那趟 tick 等于每刻把她拽回原地。
 *
 * <h2>晚一刻回,不当场回</h2>
 * 回执排进队列、下一刻交还,而不是在 {@code send} 里当场调用。当场回意味着在原版自己的
 * 跨维度搬运流程中途重入它的包处理器——那时她已经 {@code setServerLevel} 了但还没
 * {@code addDuringTeleport}。真客户端本来也要一个来回才答,晚一刻既更像真的,也不必赌
 * 原版中途的状态是自洽的。
 *
 * <h2>答话挂在服务端 tick 上,不挂实体 tick</h2>
 * {@link #answer()} 由 {@code CompanionTickDispatcher} 每刻遍历玩家列表时调用。挂
 * {@code NumenPlayer.tick()} 是不行的:实体 tick 只在她所在区块已经进入实体刻时才跑,
 * 而换维度刚落地的那片区块恰恰还没进——回执要等区块,区块要等她把加载垫盖下去,
 * 互相等着。真客户端答话从不看服务端有没有在 tick 她,这里同理。
 */
@com.dwinovo.numen.api.Internal
public final class FakeClient {

    private final NumenPlayer player;

    /**
     * 待回的传送编号,-1 表示没有。
     *
     * <p>服务端每次 {@code connection.teleport()} 都记下一个编号等客户端报数,而清掉
     * {@code awaitingPositionFromClient}、把身体落定到目标点、以及<b>清掉"正在换维度"这个
     * 旗标</b>的唯一一处就是收到这个回执时
     * ({@code ServerGamePacketListenerImpl.handleAcceptTeleportPacket})。没有超时兜底。
     * 回执不来,她就永远卡在"正在换维度":传送门冷却不再递减(原版只在不换维度时减),
     * 而且一直免疫伤害(那个旗标直接进 {@code isInvulnerableTo})。
     *
     * <p>只留最后一个编号不是偷懒:服务端手里也只有一个 {@code awaitingTeleport},
     * 早先的编号对不上,原版收到了也照样忽略。
     */
    private int awaitingTeleport = -1;

    /** 服务端对她说的话,折叠后交成事件。 */
    private final ServerMessages messages;

    /** 她正在执行的那条指令收话的地方;没在执行指令时为 null。 */
    private Consumer<Component> commandOutput;

    FakeClient(NumenPlayer player) {
        this.player = player;
        this.messages = new ServerMessages((text, overlay, repeats) -> NumenEvents.serverMessage(player, text,
                overlay, repeats, ServerMessages.WINDOW_TICKS / 20));
    }

    /** 服务端刚往"客户端"发了一个包。 */
    public void onOutbound(Packet<?> packet) {
        if (packet instanceof ClientboundPlayerPositionPacket teleport) {
            awaitingTeleport = teleport.getId();
        } else if (packet instanceof ClientboundSystemChatPacket said) {
            heard(said.content(), said.overlay());
        }
    }

    /**
     * 以她的名义执行一条指令:{@code command} 跑的期间服务端对她说的每一句都是这条指令说的,交给 {@code output},
     * 与指令回给来源的话收在同一份回执里。指令执行是同步的,这期间到她这儿的话不会有别的出处。
     */
    public void runCommand(Consumer<Component> output, Runnable command) {
        Consumer<Component> outer = commandOutput;
        commandOutput = output;
        try {
            command.run();
        } finally {
            commandOutput = outer;
        }
    }

    private void heard(Component content, boolean overlay) {
        if (commandOutput != null) {
            commandOutput.accept(content);
            return;
        }
        String text = content.getString();
        if (player.isDeadOrDying() && text.equals(player.deathMessage())) {
            return;
        }
        messages.heard(text, overlay, player.server.getTickCount());
    }

    /** 每刻一次:把攒下的回执交还服务端,剩下的全由原版自己做;折叠窗口到了的话交出去。 */
    public void answer() {
        messages.tick(player.server.getTickCount());
        if (awaitingTeleport < 0) {
            return;
        }
        int id = awaitingTeleport;
        awaitingTeleport = -1;
        player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id));
    }
}
