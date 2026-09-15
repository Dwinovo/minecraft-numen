package com.dwinovo.numen.event;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.EventOutbox;
import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.payload.NumenEventPayload;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.reflex.Reflex;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * <b>世界事件的唯一发出口。</b>常驻任务链、任务收尾、维度穿越、以及第三方内容包,
 * 全都往这里写——一个类,一个方法。
 *
 * <h2>为什么收成一个口</h2>
 * 多开一条发事件的路,就多一套"带不带时间戳""主人离线怎么办""攒不攒",而它们
 * 必然分叉:同样是"主人下线时任务做完了",一条路直接丢、另一条能留六条。
 * 一个问题只能有一个答案,所以只有这一个入口。
 *
 * <h2>种类查表</h2>
 * 发的是哪一种事由类型表({@link EventTypes})说了算:条目的 {@code type} 就是种类,
 * {@code <event kind="…">} 里的 kind 由这里用同一个 id 拼上。表里没登记的种类、以及登记了却不是
 * 世界的事的类型(主人的话、目标续跑、整理与清空),在这里当场拒绝——那是发送方写错了。
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
 * 或者主人说话时搭车。类型表说某种事恒为急件的,发送方怎么标都是急件;其余由
 * 发事件的人判断——判断错了主人会觉得同伴很吵,那是内容包自己的名声。
 *
 * <p>服务端专用。
 */
public final class NumenEvents {

    private NumenEvents() {}

    /**
     * 她饿了。<b>急</b> —— 她不会自己吃,主人不知道就没人管,饱食归零会开始掉血。
     * 去抖在 {@code NumenPlayer.pollGotHungry}:一轮饥饿只发一条。
     */
    public static void gotHungry(NumenPlayer companion, int foodLevel) {
        emit(companion, EventTypes.HUNGRY, null,
                "you are hungry (" + foodLevel + "/20) and you do not eat on your own — "
                        + "call eat with something from your inventory, or go get food",
                true);
    }

    /**
     * 某个本能替身体做了一件事。{@code reflex} 属性写的是它在本能名册里的登记名({@link Reflex#id}),
     * 不另起一套名字。永远不急:身体已经自己应对过了,这条是让她和翻聊天流的主人看得懂刚才发生了什么,
     * 攒着搭下一轮的车就够。
     */
    public static void reflex(NumenPlayer companion, Reflex reflex, String text) {
        emit(companion, EventTypes.REFLEX, Map.of("reflex", reflex.id()), text, false);
    }

    /**
     * 主人挨打了。急不急按血线分档:安全区只是消息(攒着搭车,她下次开口自然带一句);
     * 跌进危险区(与饥饿同一条"原版跑不动"的线)才是急件。这条事件<b>不碰身体</b>——
     * 去不去救永远是她的决定。检测与去抖在 {@code OwnerHurtWatch}。
     */
    public static void ownerHurt(NumenPlayer companion, String attacker,
                                 float hp, float maxHp, double distance, boolean urgent) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("by", attacker);
        attrs.put("owner_hp", Math.round(hp) + "/" + Math.round(maxHp));
        attrs.put("distance", String.valueOf(Math.round(distance)));
        String text = urgent
                ? "your owner is in DANGER: " + attacker + " has them down to " + Math.round(hp)
                        + "/" + Math.round(maxHp) + " HP, about " + Math.round(distance)
                        + " blocks from you — decide now whether to go help"
                : "your owner just took a hit from " + attacker + " (" + Math.round(hp) + "/"
                        + Math.round(maxHp) + " HP, about " + Math.round(distance)
                        + " blocks from you) — they can likely handle it; your call";
        emit(companion, EventTypes.OWNER_HURT, attrs, text, urgent);
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
        emit(companion, EventTypes.TASK_FINISHED, attrs, message, !"stopped".equals(status));
    }

    /**
     * 发一条世界事件。主人在线直接送达,离线进出箱等他回来。
     *
     * @param type   事件种类,类型表里登记过的世界的事
     * @param attrs  拼进 {@code <event>} 的属性,按迭代顺序;没有就给 null
     * @param urgent 她不知道就会做错事 → 立刻开一轮;否则攒着搭车。类型表说恒为急件的种类不看它
     * @throws IllegalArgumentException 种类没登记,或者登记的不是世界的事
     */
    public static void emit(NumenPlayer companion, String type, Map<String, String> attrs,
                            String text, boolean urgent) {
        MinecraftServer server = companion.level().getServer();
        EventQueue.Entry entry = entry(server.overworld().getDayTime(), type, attrs, text,
                System.currentTimeMillis(), urgent);
        UUID uuid = companion.getUUID();
        ServerPlayer owner = companion.resolveOwnerPlayer();
        route(uuid, entry,
                owner == null ? null : payload -> {
                    Services.NETWORK.sendToPlayer(owner, payload);
                    Constants.LOG.info("[numen-event] {} kind={}{} → 客户端", uuid, type,
                            urgent ? " URGENT" : "");
                },
                kept -> {
                    // 主人不在:留着。他下线期间她照样在干活,回来该知道发生了什么。
                    EventOutbox outbox = EventOutbox.get(server);
                    outbox.put(uuid, kept.type(), kept.text(), kept.ts(), kept.urgent());
                    Constants.LOG.info("[numen-event] {} kind={}{} → 暂存(主人离线,已攒 {} 条)",
                            uuid, type, urgent ? " URGENT" : "", outbox.peek(uuid).size());
                });
    }

    /**
     * 一条造好的事件往哪去:主人在线({@code toOwner} 不为 null)装进一个包直送他的客户端,
     * 离线交给 {@code keep} 进出箱。
     *
     * <p>纯逻辑,不碰网络与存档——留这个缝是为了"在线直送、离线进出箱"能被单测钉住。
     */
    static void route(UUID companion, EventQueue.Entry entry,
                      Consumer<NumenEventPayload> toOwner, Consumer<EventQueue.Entry> keep) {
        if (toOwner != null) {
            toOwner.accept(new NumenEventPayload(companion, List.of(entry)));
        } else {
            keep.accept(entry);
        }
    }

    /**
     * 造一条事件条目——<b>唯一的构造口</b>。条目的类型与 {@code <event kind="…">} 取自同一个
     * {@code type},{@code day} / {@code t} 由它统一盖上。
     *
     * <p>收 {@code dayTime} 而不是 {@code MinecraftServer},所以客户端也能用同一条路
     * (死亡事件在客户端合成:那会儿身体已经不在了)。两侧共用这一个构造口,
     * 才不会出现"最该有时间的那条事件恰好没盖上时间"。
     *
     * @throws IllegalArgumentException 种类没登记,或者登记的不是世界的事
     */
    public static EventQueue.Entry entry(long dayTime, String type, Map<String, String> attrs,
                                         String text, long now, boolean urgent) {
        if (!EventTypes.isRegistered(type)) {
            throw new IllegalArgumentException("事件种类没登记过:" + type);
        }
        if (EventTypes.get(type).fromOwner()) {
            throw new IllegalArgumentException(type + " 不是世界上发生的事,不能当事件发");
        }
        return new EventQueue.Entry(type, compose(dayTime, type, attrs, text), now, urgent);
    }

    /** 拼 {@code <event>}:kind 就是条目的类型,盖上游戏内时间戳。 */
    private static String compose(long dayTime, String type, Map<String, String> attrs, String text) {
        StringBuilder sb = new StringBuilder("<event kind=\"").append(type).append('"');
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
