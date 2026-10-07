package com.dwinovo.numen.event;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.EventOutbox;
import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.script.ScriptCall;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.payload.NumenEventPayload;
import com.dwinovo.numen.task.reflex.Reflex;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                        + "numen.inv.eat something from your inventory, or go get food",
                true);
    }

    /**
     * 她的背包一格空的都没有了,碰到的一件东西放不下、留在了地上。<b>急</b>——她不知道就会接着挖、接着捡,东西都落在地上。
     * 判据与去抖在 {@code NumenPlayer.touchedItem} 与 {@code pollInventoryFull}:一轮满只发一条,背包又有空格才复位。
     */
    public static void inventoryFull(NumenPlayer companion, NumenPlayer.LeftBehind left) {
        String item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(left.stack().getItem()).toString();
        emit(companion, EventTypes.INVENTORY_FULL, Map.of("item", item),
                "your backpack is full: " + item + " x" + left.stack().getCount() + " at "
                        + com.dwinovo.numen.sdk.Positions.literal(left.pos()) + " did not fit and stayed on the ground, "
                        + "and anything else that does not stack onto what you carry stays on the ground too. Make room "
                        + "with numen.inv.drop (what you can spare) or by putting things into a chest (numen.use.block "
                        + "opens it, w:put fills it); then numen.work.collect picks up what is still on the ground.",
                true);
    }

    /**
     * 她达成了一个进度,奖励进了她的身上。不急:东西已经在她背包里,她下次开口自然带上。{@code change} 是同一刻里她背包与经验实际的变化
     * ({@code Belongings}),不是从进度配置推算的——战利品表每次抽的不一样。
     */
    public static void advancementReward(NumenPlayer companion, String id, String title, String change) {
        emit(companion, EventTypes.ADVANCEMENT_REWARD, Map.of("id", id),
                "you completed the advancement \"" + title + "\" and its reward reached you: " + change, false);
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

    /**
     * 服务端对她说了一句话(系统聊天或动作栏),原文照交。{@code repeats} 是这一句在上一次交出去之后又说了几遍——
     * 同一句刷屏只在每个折叠窗口里交一次,收拢与窗口在 {@code ServerMessages}。不急,也不叫醒她:捎带投递,见类型表。
     *
     * @param overlay  显示在动作栏(屏幕中下方那一行)而不是聊天栏
     * @param repeats  上次交出之后又说的遍数;0 = 头一回说
     * @param window   折叠窗口有多长,秒;{@code repeats} 为 0 时不用
     */
    public static void serverMessage(NumenPlayer companion, String text, boolean overlay, int repeats, int window) {
        emit(companion, EventTypes.SERVER_MESSAGE, Map.of("where", overlay ? "action_bar" : "chat"),
                repeats == 0 ? text
                        : text + " (said " + repeats + " more time" + (repeats == 1 ? "" : "s") + " in the last "
                                + window + "s)",
                false);
    }

    /** 异步任务收尾。{@code status} ∈ done / failed / timeout / stopped / interrupted。
     *  <p>done/failed/timeout 是急的:她派出去的活有了结果,该当场决定下一步。
     *  stopped 是主人自己按的停止,他知道,不必吵他。
     *  <p>她读到的是它的实际账;整份结果(值、失败的种类与下一步)随条目一起到,给等这件活的程序。
     *
     *  @param fn 派它的 API 函数,值按它的返回类型写;不出自 API 函数的是 null */
    public static void taskFinished(NumenPlayer companion, String taskId, String tool,
                                    String status, TaskResult result, com.dwinovo.numen.sdk.ApiFunction fn) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put(TASK_ID, taskId);
        attrs.put("task", tool);
        attrs.put(STATUS, status);
        emit(companion, EventTypes.TASK_FINISHED, attrs, result.message(), !"stopped".equals(status),
                com.dwinovo.numen.sdk.Dispatcher.ended(result, fn));
    }

    /**
     * 一段被切断的程序在服务端交出的回执,作为一条事件交给她:事件正文就是那份回执的文字,不另写一份。主人客户端在收到一份已经作废的
     * 那一批的程序结果时造它。
     *
     * @param program 程序的编号
     * @param receipt 回执的文字(服务端写的那一份)
     */
    public static EventQueue.Entry programStopped(long dayTime, String program, String receipt, long now) {
        return entry(dayTime, EventTypes.PROGRAM_STOPPED, Map.of("program", program), receipt, now, false);
    }

    /** 服务端发出的每一条事件先交给它们:在服务端跑的程序等它派的活的收尾、也据此判断要不要停({@code ServerPrograms})。 */
    private static final List<Watcher> WATCHERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 看着服务端发出的每一条事件,按同伴。 */
    @FunctionalInterface
    public interface Watcher {

        /**
         * 一条事件发出了。
         *
         * @return 这条事件归了看着它的一方(程序等着的那件活的收尾,账写进了程序的回执):不再送给主人的大脑,一件活的收尾只说一次
         */
        boolean takes(UUID companion, EventQueue.Entry entry);
    }

    /** 看着服务端发出的每一条事件。 */
    public static void watch(Watcher watcher) {
        WATCHERS.add(watcher);
    }

    /** task_finished 里写着是哪件活、收尾成什么样的属性:{@link #taskFinished} 按它写,{@link #finishOf} 按它读。 */
    private static final String TASK_ID = "id";
    private static final String STATUS = "status";
    /** 事件开头那一截里的一个属性;属性值经 {@link #escape} 转义过,里面不会有引号和尖括号。 */
    private static final Pattern HEAD = Pattern.compile("^<event [^>]*>");
    /** 事件正文:开头之后到收尾标记之前。 */
    private static final Pattern BODY = Pattern.compile("^<event [^>]*>(.*)</event>$", Pattern.DOTALL);

    /**
     * 这条输入是哪件后台活的收尾:一条 task_finished 事件就是它的编号、收尾状态与交代的话,别的输入是 null。事件的样子只在
     * 这里拼({@link #compose}),也在这里读回;内脑的派发器据此知道它在等的那件活做完了、做成了没有。
     */
    public static ScriptCall.Finish finishOf(EventQueue.Entry entry) {
        if (!EventTypes.TASK_FINISHED.equals(entry.type())) {
            return null;
        }
        Matcher head = HEAD.matcher(entry.text());
        if (!head.find()) {
            return null;
        }
        String id = attribute(head.group(), TASK_ID);
        if (id == null) {
            return null;
        }
        Matcher body = BODY.matcher(entry.text());
        String status = attribute(head.group(), STATUS);
        return new ScriptCall.Finish(id, status == null ? "" : status, body.find() ? unescape(body.group(1)) : "",
                entry.result());
    }

    /** 开头那一截里一个属性的值;没有是 null。 */
    private static String attribute(String head, String name) {
        Matcher m = Pattern.compile(" " + name + "=\"([^\"]*)\"").matcher(head);
        return m.find() ? unescape(m.group(1)) : null;
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
        emit(companion, type, attrs, text, urgent, null);
    }

    /** 同上,条目另带一件身体活的结果({@link EventQueue.Entry#result})。 */
    private static void emit(NumenPlayer companion, String type, Map<String, String> attrs,
                             String text, boolean urgent, com.google.gson.JsonObject result) {
        MinecraftServer server = companion.level().getServer();
        EventQueue.Entry plain = entry(server.overworld().getDayTime(), type, attrs, text,
                System.currentTimeMillis(), urgent);
        EventQueue.Entry entry = new EventQueue.Entry(plain.type(), plain.text(), plain.ts(), plain.urgent(), result);
        UUID uuid = companion.getUUID();
        boolean taken = false;
        for (Watcher watcher : WATCHERS) {
            taken |= watcher.takes(uuid, entry);
        }
        if (!taken) {
            deliver(companion, entry);
        }
    }

    /**
     * 一条造好的事件送给主人的大脑:主人在线直接送达,离线进出箱等他回来。归了在跑的程序、之后程序却没能用上的收尾也经这里。
     */
    public static void deliver(NumenPlayer companion, EventQueue.Entry entry) {
        MinecraftServer server = companion.level().getServer();
        UUID uuid = companion.getUUID();
        String type = entry.type();
        boolean urgent = entry.urgent();
        ServerPlayer owner = companion.resolveOwnerPlayer();
        route(uuid, entry,
                owner == null ? null : payload -> {
                    NumenNetwork.sendToPlayer(owner, payload);
                    Constants.LOG.info("[numen-event] {} kind={}{} → 客户端", uuid, type,
                            urgent ? " URGENT" : "");
                },
                kept -> {
                    // 主人不在:留着。他下线期间她照样在干活,回来该知道发生了什么。
                    EventOutbox outbox = EventOutbox.get(server);
                    outbox.put(uuid, kept);
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
        requireWorldEvent(type);
        return new EventQueue.Entry(type, compose(dayTime, type, attrs, text), now, urgent);
    }

    /**
     * 这个种类能不能当一件世界上发生的事发出去:登记过,且不是主人那几行(主人的话、目标续跑、清空、整理)。
     * 发事件的每个入口都问这一处——服务端的发出口、主人客户端的门。
     *
     * @throws IllegalArgumentException 种类没登记,或者登记的不是世界的事
     */
    public static void requireWorldEvent(String type) {
        if (!EventTypes.isRegistered(type)) {
            throw new IllegalArgumentException("事件种类没登记过:" + type);
        }
        if (EventTypes.get(type).fromOwner()) {
            throw new IllegalArgumentException(type + " 不是世界上发生的事,不能当事件发");
        }
    }

    /**
     * 主人客户端那扇门收不收这个种类:主人的话({@code query}),或者一件登记过的世界事件。
     * 插件的门和客户端的入口都问这一处,有没有主人客户端都一样地拒。
     *
     * @throws IllegalArgumentException 两样都不是
     */
    public static void requireClientInput(String type) {
        if (!EventTypes.QUERY.equals(type)) {
            requireWorldEvent(type);
        }
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

    /**
     * 同一条事件换一段正文:开头的 {@code <event …>} 连同种类、时刻与编号原样留着,只把正文换成 {@code body}。
     * 包装不下时缩短正文用它({@code NumenEventPayload#shrunk}),{@link #finishOf} 照样读得出是哪件活。
     * 不是 {@code <event>} 的条目没有开头可留,整段换成 {@code body}。
     */
    public static String withBody(String text, String body) {
        if (!text.startsWith("<event ")) {
            return body;
        }
        return text.substring(0, text.indexOf('>') + 1) + escape(body) + "</event>";
    }

    /** 游戏内时刻 HH:mm。原版 0 刻 = 早上 6 点。 */
    static String clockOf(long dayTime) {
        long inDay = Math.floorMod(dayTime, 24000L);
        long minutes = (inDay * 60L / 1000L + 6L * 60L) % (24L * 60L);
        return String.format("%02d:%02d", minutes / 60L, minutes % 60L);
    }

    /** {@link #escape} 的反方向。 */
    private static String unescape(String s) {
        return s.replace("&quot;", "\"").replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&");
    }

    /** XML 属性/正文转义——事件正文里可能有实体名、物品名,是玩家能控制的输入。 */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
