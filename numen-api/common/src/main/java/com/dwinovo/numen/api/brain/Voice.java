package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.loop.LoopEvent;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 她说出口了——服务端<b>唯一的一处</b>。循环里她的一条回复落了库(含开工前顺嘴的一句),只要有话:
 * <ul>
 *   <li>同会话的其他成员旁听到一份({@link Conversations#heard});</li>
 *   <li>主人没装客户端时,话作为只发给他的系统消息说出口(装了客户端的,同一句话随 {@link Feed.Replied} 推给他,
 *       由客户端画气泡、聊天行、语音);</li>
 *   <li>渠道来访着的(主人从 QQ 等渠道说的话,见 {@link Visits}),话回给那个渠道。</li>
 * </ul>
 * 外部大脑替她说的话以后也走这一处。
 */
final class Voice {

    private Voice() {}

    /**
     * @param ownerHasClient 主人此刻有可用的客户端
     * @param tellOwner      没装客户端时,把这句话只告诉主人
     */
    static void attach(CompanionLoop loop, Conversations rooms, BooleanSupplier ownerHasClient,
                       Consumer<String> tellOwner) {
        loop.subscribe(event -> {
            if (event instanceof LoopEvent.AssistantMessage message) {
                String said = message.turn().content() == null ? "" : message.turn().content().strip();
                if (said.isEmpty()) {
                    return;
                }
                rooms.heard(loop.companion(), said);
                Visits.said(loop.companion(), said);
                if (!ownerHasClient.getAsBoolean()) {
                    tellOwner.accept(said);
                }
            }
        });
    }
}
