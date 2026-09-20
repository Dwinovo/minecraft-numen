package com.dwinovo.numen.entity;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;

/**
 * 客户端那一半。这具身体没有客户端,凡是服务端"发出去、然后等对面回话"的握手都会永远悬着;
 * 这里代替客户端把回话送回去。
 *
 * <h2>边界:只回握手,不报物理</h2>
 * 这里只认<b>服务端会等回执</b>的下行包。走路、视角这些"客户端本地算、再上报给服务端"的
 * 东西不归这儿——那是 {@link NumenPlayer#tick()} 里自己跑的物理:服务端算出来就是结果,
 * 没有谁在等一个回复。所以这个类不会长成一个客户端实现,它的面就是原版那几处握手。
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

    FakeClient(NumenPlayer player) {
        this.player = player;
    }

    /** 服务端刚往"客户端"发了一个包。 */
    public void onOutbound(Packet<?> packet) {
        if (packet instanceof ClientboundPlayerPositionPacket teleport) {
            awaitingTeleport = teleport.getId();
        }
    }

    /** 每刻一次:把攒下的回执交还服务端,剩下的全由原版自己做。 */
    public void answer() {
        if (awaitingTeleport < 0) {
            return;
        }
        int id = awaitingTeleport;
        awaitingTeleport = -1;
        player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id));
    }
}
