package com.dwinovo.numen.event;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.EventOutbox;
import com.dwinovo.numen.event.EventTypes;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.payload.NumenEventPayload;
import com.dwinovo.numen.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>世界事件的唯一入口。</b>常驻任务链、任务收尾、维度穿越、以及第三方内容包,
 * 全都往这里写——一个类,一个方法。
 *
 * <h2>为什么收成一个口</h2>
 * 多开一条发事件的路,就多一套"带不带时间戳""主人离线怎么办""攒不攒",而它们
 * 必然分叉:同样是"主人下线时任务做完了",一条路直接丢、另一条能留六条。
 * 一个问题只能有一个答案,所以只有这一个入口。
 *
 * <h2>两件事这里一定做</h2>
 * <ol>
 *   <li><b>盖时间戳</b>——每条事件都带游戏内日期与时刻。模型能自己判断哪些信息
 *       过期了(死前捡的铁矿在死亡地点掉了),我们就不必替它清箱;</li>
 *   <li><b>主人离线不丢</b>——进 {@link EventOutbox} 跟着存档落盘,主人登录时补发。
 *       每一种事件都享受这条,没有例外。</li>
 * </ol>
 *
 * <h2>urgent</h2>
 * {@code true} = <em>她不知道这件事,正在做的事就是错的</em>。到了客户端队列,
 * urgent 会立刻带走队列里攒的一切并开一轮;非 urgent 攒着,等够数、够久、
 * 或者主人说话时搭车。发事件的人有权判断——判断错了主人会觉得同伴很吵,
 * 那是内容包自己的名声。
 *
 * <p>服务端专用。
 */
public final class NumenEvents {

    /** 事件词汇表。新种类往这里加,别自己拼 XML。 */
    public enum Kind {
        /** 异步任务收尾(status: done / failed / timeout / stopped)。 */
        TASK_FINISHED("task_finished"),
        /** 身体自理:饿了吃、快淹死了浮上来、被打了还手。 */
        BODY_LOG("body_log"),
        /** 同伴自己跨了维度。 */
        DIMENSION_CHANGE("dimension_change"),
        /** 她死了又复活了(在客户端合成——身体那会儿已经不在了)。 */
        DEATH("death"),
        /** 她自己定的表到点了(见 {@code TimerRegistry})。提醒而已,不代表那件事完成了。 */
        TIMER("timer"),
        /** 她从床上醒了。{@code sleep} 到躺下就返回,醒来这一刻只有这条事件说得出。 */
        WOKE("woke"),
        /** 饿了 —— 她不会自己吃,得主人给或者叫她去弄。 */
        HUNGRY("hungry");

        private final String kind;

        Kind(String kind) {
            this.kind = kind;
        }

        public String kindName() {
            return kind;
        }
    }

    private NumenEvents() {}

    /**
     * 她饿了。<b>急</b> —— 她不会自己吃,主人不知道就没人管,饱食归零会开始掉血。
     * 去抖在 {@code NumenPlayer.pollGotHungry}:一轮饥饿只发一条。
     */
    public static void gotHungry(NumenPlayer companion, int foodLevel) {
        emit(companion, Kind.HUNGRY, null,
                "you are hungry (" + foodLevel + "/20) and you do not eat on your own — "
                        + "call eat with something from your inventory, or go get food",
                true);
    }

    /** 身体自理日记——常驻任务链的叙事出口。永远不急。 */
    public static void body(NumenPlayer companion, String text) {
        emit(companion, Kind.BODY_LOG, null, text, false);
    }

    /** 异步任务收尾。{@code status} ∈ done / failed / timeout / stopped。
     *  <p>done/failed/timeout 是急的:她派出去的活有了结果,该当场决定下一步。
     *  stopped 是主人自己按的停止,他知道,不必吵他。 */
    public static void taskFinished(NumenPlayer companion, String taskId, String tool,
                                    String status, String message) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("id", taskId);
        attrs.put("task", tool);
        attrs.put("status", status);
        emit(companion, Kind.TASK_FINISHED, attrs, message, !"stopped".equals(status));
    }

    /**
     * 发一条世界事件。主人在线直接送达,离线进出箱等他回来。
     *
     * @param urgent 她不知道就会做错事 → 立刻开一轮;否则攒着搭车
     */
    public static void emit(NumenPlayer companion, Kind kind, Map<String, String> attrs,
                            String text, boolean urgent) {
        if (companion == null) {
            return;
        }
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            return;
        }
        String xml = compose(server, kind, attrs, text);
        long now = System.currentTimeMillis();
        ServerPlayer owner = companion.resolveOwnerPlayer();
        if (owner != null) {
            Services.NETWORK.sendToPlayer(owner, new NumenEventPayload(
                    companion.getUUID(), EventTypes.EVENT, xml, now, urgent));
            Constants.LOG.info("[numen-event] {} kind={}{} → 客户端", companion.getUUID(),
                    kind.kind, urgent ? " URGENT" : "");
            return;
        }
        // 主人不在:留着。他下线期间她照样在干活,回来该知道发生了什么。
        EventOutbox outbox = EventOutbox.get(server);
        outbox.put(companion.getUUID(), EventTypes.EVENT, xml, now, urgent);
        Constants.LOG.info("[numen-event] {} kind={}{} → 暂存(主人离线,已攒 {} 条)",
                companion.getUUID(), kind.kind, urgent ? " URGENT" : "",
                outbox.peek(companion.getUUID()).size());
    }

    /** 组装 XML,盖上游戏内时间戳。 */
    private static String compose(MinecraftServer server, Kind kind, Map<String, String> attrs, String text) {
        return compose(server.overworld().getDayTime(), kind, attrs, text);
    }

    /**
     * 造一条 {@code <event>} —— <b>唯一的构造口</b>,{@code day} / {@code t} 由它统一盖上。
     *
     * <p>收 {@code dayTime} 而不是 {@code MinecraftServer},所以客户端也能用同一条路
     * (死亡事件在客户端合成:那会儿身体已经不在了)。两侧共用这一个构造口,
     * 才不会出现"最该有时间的那条事件恰好没盖上时间"。
     */
    public static String compose(long dayTime, Kind kind, Map<String, String> attrs, String text) {
        StringBuilder sb = new StringBuilder("<event kind=\"").append(kind.kind).append('"');
        sb.append(" day=\"").append(dayTime / 24000L).append('"');
        sb.append(" t=\"").append(clockOf(dayTime)).append('"');
        if (attrs != null) {
            for (Map.Entry<String, String> e : attrs.entrySet()) {
                sb.append(' ').append(e.getKey()).append("=\"").append(escape(e.getValue())).append('"');
            }
        }
        return sb.append('>').append(escape(text)).append("</event>").toString();
    }

    /** 游戏内时刻 HH:mm。原版 0 刻 = 早上 6 点。 */
    static String clockOf(long dayTime) {
        long inDay = Math.floorMod(dayTime, 24000L);
        long minutes = (inDay * 60L / 1000L + 6L * 60L) % (24L * 60L);
        return String.format("%02d:%02d", minutes / 60L, minutes % 60L);
    }

    /** XML 属性/正文转义——事件正文里可能有实体名、物品名,是玩家能控制的输入。 */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
