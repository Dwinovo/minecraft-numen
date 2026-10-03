package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.entity.NumenPlayer;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTamedEvent;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTaskEnableEvent;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTombstoneEvent;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.data.MaidNumAttachment;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.SchedulePos;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.TabIndex;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.init.InitDataAttachment;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import com.github.tartaricacid.touhoulittlemaid.network.message.MaidConfigPackage;
import com.github.tartaricacid.touhoulittlemaid.network.message.MaidTaskPackage;
import com.github.tartaricacid.touhoulittlemaid.network.message.ToggleTabPackage;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidInfo;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidWorldData;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.ServerPayloadContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 她的女仆在车万女仆那一侧的样子——服务端<b>唯一</b>碰车万女仆类的地方(客户端那一侧是 {@link Tlm},喂食的那一刻是
 * {@code mixin/TaskFeedOwnerMixin})。别的类只拿到原版的 {@link Entity} 和这里交出去的名字与数字,所以只登记命令的
 * 那一段(联动的防漂移测试就只跑那一段)一个车万女仆的类都不加载。
 *
 * <h2>做事:调车万女仆自己的包</h2>
 * 人在女仆界面里按的按钮——切工作模式、家模式、拾取、骑乘、日程、切页——每一个都是一个客户端发来的包,判据全写在包的
 * {@code handle} 里:是不是主人({@code isOwnedBy})、这个工作模式此刻开不开得了({@code MaidTaskEnableEvent} 与
 * {@code isEnable})、家模式离日程点够不够近。这里构造同一个包、以她为发送者直接调它的 {@code handle},判据就只留在车万
 * 女仆那一处;调完读回女仆的状态,是什么就报什么。包被判据挡下时 {@code handle} 什么都不说(给人的提示走下行包,她收不到),
 * 所以回执以读回为准。
 *
 * <p>不从她的连接把包注进去:她的连接没有协商过车万女仆的频道,NeoForge 收到这种包会把连接断开
 * ({@code NetworkRegistry.handleModdedPayload})。
 *
 * <p>上下文用 NeoForge 为一次真实的上行包构造的那个 {@link ServerPayloadContext},不自己实现 {@link IPayloadContext}:
 * 那个接口标着 {@code @ApiStatus.NonExtendable},NeoForge 随时可以往里加方法;{@code ServerPayloadContext} 标着
 * {@code @ApiStatus.Internal},构造参数变了编译当场就过不去。用它,{@code handle} 看到的就是真包看到的:发送者是她、
 * 方向是上行、{@code enqueueWork} 在主线程上当场执行(命令本来就在主线程上跑)。
 */
final class Maids {

    /**
     * 女仆界面还开不开着,车万女仆每刻用 {@code canInteractWithEntity(maid, 4.0)} 判({@code AbstractMaidContainer.stillValid});
     * 界面上的按钮也就只有离这么近才按得到。插件的动作照同一个距离判够不够得着。
     */
    private static final double GUI_REACH_BUFFER = 4.0;

    private Maids() {}

    /** 界面的一页:{@code tlm open} 能打开的那几页,和车万女仆界面边上的页签是同一组编号({@link TabIndex})。 */
    enum Tab {
        BACKPACK(TabIndex.MAIN), BAUBLE(TabIndex.BAUBLE), CURIOS(TabIndex.CURIOS);

        private final int index;

        Tab(int index) {
            this.index = index;
        }

        /** 命令行上的写法。 */
        String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Tab byWord(String word) {
            return valueOf(word.toUpperCase(Locale.ROOT));
        }
    }

    /**
     * 一只女仆在设置页上的四样:家模式、拾取、骑乘、日程。日程写成命令行上的小写词({@code day}/{@code night}/{@code all}),
     * 就是 {@link MaidSchedule} 的名字。
     */
    record Settings(boolean home, boolean pickup, boolean ride, String schedule) {}

    static boolean is(Entity entity) {
        return entity instanceof EntityMaid;
    }

    /** 她现在够不够得着这只女仆的界面,见 {@link #GUI_REACH_BUFFER}。 */
    static boolean inReach(ServerPlayer her, Entity maid) {
        return her.canInteractWithEntity(maid, GUI_REACH_BUFFER);
    }

    /** 车万女仆认不认她是这只女仆的主人——包的判据用的就是这一句,这里只在说明它为什么没照做时读。 */
    static boolean ownedBy(Entity maid, Player her) {
        return ((EntityMaid) maid).isOwnedBy(her);
    }

    /** 这只女仆的主人:在线的写名字,不在线的写 UUID;野生的为 null。 */
    static String owner(Entity entity) {
        EntityMaid maid = (EntityMaid) entity;
        UUID id = maid.getOwnerUUID();
        if (id == null) {
            return null;
        }
        LivingEntity owner = maid.getOwner();
        return owner != null ? owner.getName().getString() : id.toString();
    }

    // ---- 读 ----

    /** 她名下、此刻在世界里的女仆,各维度都算:同一维度的在前、由近及远,别的维度的在后。 */
    static List<Entity> loaded(ServerPlayer her) {
        List<Entity> found = new ArrayList<>();
        for (ServerLevel level : her.server.getAllLevels()) {
            found.addAll(level.getEntities(EntityMaid.TYPE,
                    maid -> maid.isAlive() && her.getUUID().equals(maid.getOwnerUUID())));
        }
        found.sort(Comparator.comparingDouble(maid -> maid.level() == her.level()
                ? her.distanceToSqr(maid) : Double.MAX_VALUE));
        return found;
    }

    /**
     * 她名下、待在没加载的区块里的女仆:车万女仆在女仆离开世界时记下的最后位置。它的存档({@code MaidWorldData})挂在主世界上,
     * 服务器开着主世界就在。
     */
    static List<JsonObject> away(ServerPlayer her) {
        return records(MaidWorldData.get(her.level()).getInfos(her.getUUID()));
    }

    /** 她的女仆死后留下、还没被取空的墓碑,记在同一份存档里。 */
    static List<JsonObject> tombstones(ServerPlayer her) {
        return records(MaidWorldData.get(her.level()).getTombstones(her.getUUID()));
    }

    /** 存档里记着的那几条({@code MaidCommands.RECORD}):名字、位置、维度;这个主人一条都没记过时车万女仆给 null。 */
    private static List<JsonObject> records(List<MaidInfo> infos) {
        List<JsonObject> rows = new ArrayList<>();
        if (infos == null) {
            return rows;
        }
        for (MaidInfo info : infos) {
            JsonObject row = new JsonObject();
            row.addProperty("name", info.getName().getString());
            row.add("pos", Shapes.pos(info.getChunkPos()));
            row.addProperty("dimension", info.getDimension());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 一只加载着的女仆此刻的样子({@code MaidCommands.MAID_CLASS}):一只实体(主人照 {@code scan.entities} 的说法),加上她的模型、
     * 工作、设置与好感等级。
     */
    static JsonObject row(Entity entity, NumenPlayer her) {
        EntityMaid maid = (EntityMaid) entity;
        JsonObject row = Shapes.entity(maid);
        row.addProperty("model", maid.getModelId());
        row.addProperty("task", maid.getTask().getUid().toString());
        row.addProperty("schedule", schedule(maid.getSchedule()));
        row.addProperty("home", maid.isHomeModeEnable());
        row.addProperty("hp", tenth(maid.getHealth()));
        row.addProperty("max_hp", tenth(maid.getMaxHealth()));
        row.addProperty("favorability_level", maid.getFavorabilityManager().getLevel());
        row.addProperty("sitting", maid.isMaidInSittingPose());
        if (maid.level() == her.level()) {
            row.addProperty("distance", tenth(her.distanceTo(maid)));
        } else {
            row.addProperty("dimension", maid.level().dimension().location().toString());
        }
        UUID owner = maid.getOwnerUUID();
        if (owner != null) {
            row.addProperty("owner", owner.equals(her.getUUID()) ? "you"
                    : her.isOwnedByPlayer(owner) ? "your owner" : owner(entity));
        }
        return row;
    }

    /**
     * 一只女仆的详情({@code tlm.maid} 的数据,不含工作模式):清单里的她({@link #row}),加上设置页的其余几样、好感、背包、
     * 日程点。
     */
    static JsonObject detail(Entity entity, NumenPlayer her) {
        EntityMaid maid = (EntityMaid) entity;
        JsonObject out = new JsonObject();
        out.add("maid", row(entity, her));
        out.addProperty("pickup", maid.isPickup());
        out.addProperty("ride", maid.isRideable());
        out.addProperty("favorability", maid.getFavorability());
        out.addProperty("favorability_to_next_level", maid.getFavorabilityManager().nextLevelPoint());
        out.addProperty("backpack", maid.getMaidBackpackType().getId().toString());
        if (maid.isHomeModeEnable()) {
            out.add("home_center", Shapes.pos(maid.getRestrictCenter()));
            out.addProperty("home_radius", tenth(maid.getRestrictRadius()));
        }
        SchedulePos points = maid.getSchedulePos();
        if (points.isConfigured()) {
            JsonObject at = new JsonObject();
            at.add("work", Shapes.pos(points.getWorkPos()));
            at.add("idle", Shapes.pos(points.getIdlePos()));
            at.add("sleep", Shapes.pos(points.getSleepPos()));
            at.addProperty("dimension", points.getDimension().toString());
            out.add("schedule_points", at);
        }
        return out;
    }

    /**
     * 界面任务列表上的每一个工作模式(不含隐藏的),各一行:能不能切过去,以及车万女仆给这个模式列的条件此刻满没满足。
     * "能不能切"问的是车万女仆的任务列表给每个按钮问的同一组问题,见 {@link #switchable}。
     */
    static List<Map<String, Object>> tasks(Entity entity) {
        EntityMaid maid = (EntityMaid) entity;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (IMaidTask task : TaskManager.getNotHiddenTaskList(maid)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task", task.getUid().toString());
            if (task.getUid().equals(maid.getTask().getUid())) {
                row.put("current", true);
            }
            List<Pair<String, Predicate<EntityMaid>>> toEnable = new ArrayList<>();
            row.put("can_switch", switchable(task, maid, toEnable));
            if (!toEnable.isEmpty()) {
                row.put("to_enable", met(toEnable, maid));
            }
            List<Pair<String, Predicate<EntityMaid>>> conditions = task.getConditionDescription(maid);
            if (!conditions.isEmpty()) {
                row.put("works_with", met(conditions, maid));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 车万女仆此刻开不开这个工作模式:先问别的模组({@code MaidTaskEnableEvent},取消了就不开),再问模式自己
     * ({@code isEnable});空闲总能切。它的任务列表画每个按钮、切模式的包受理之前,问的都是这一组;没开的,开它要的条件
     * 收进 {@code toEnable}。
     */
    private static boolean switchable(IMaidTask task, EntityMaid maid, List<Pair<String, Predicate<EntityMaid>>> toEnable) {
        if (task == TaskManager.getIdleTask()) {
            return true;
        }
        if (NeoForge.EVENT_BUS.post(new MaidTaskEnableEvent(task, maid, toEnable)).isCanceled()) {
            return false;
        }
        if (!task.isEnable(maid)) {
            toEnable.addAll(task.getEnableConditionDesc(maid));
            return false;
        }
        return true;
    }

    private static Map<String, Boolean> met(List<Pair<String, Predicate<EntityMaid>>> conditions, EntityMaid maid) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Pair<String, Predicate<EntityMaid>> condition : conditions) {
            out.put(condition.getFirst(), condition.getSecond().test(maid));
        }
        return out;
    }

    /** 车万女仆有没有这个工作模式。 */
    static boolean taskExists(ResourceLocation task) {
        return TaskManager.findTask(task).isPresent();
    }

    /** 路径与 {@code task} 相同的那些工作模式:写错命名空间时指给她看。 */
    static List<String> tasksNamed(String path) {
        List<String> out = new ArrayList<>();
        for (ResourceLocation id : TaskManager.getTaskMap().keySet()) {
            if (id.getPath().equals(path)) {
                out.add(id.toString());
            }
        }
        return out;
    }

    /** 这只女仆现在的工作模式。 */
    static String task(Entity maid) {
        return ((EntityMaid) maid).getTask().getUid().toString();
    }

    /** 这只女仆此刻开不开 {@code task},没开的话开它要的条件各自满没满足;开着返回 null。 */
    static Map<String, Boolean> notEnabled(Entity entity, ResourceLocation task) {
        EntityMaid maid = (EntityMaid) entity;
        IMaidTask found = TaskManager.findTask(task).orElseThrow();
        List<Pair<String, Predicate<EntityMaid>>> toEnable = new ArrayList<>();
        return switchable(found, maid, toEnable) ? null : met(toEnable, maid);
    }

    static Settings settings(Entity entity) {
        EntityMaid maid = (EntityMaid) entity;
        return new Settings(maid.isHomeModeEnable(), maid.isPickup(), maid.isRideable(), schedule(maid.getSchedule()));
    }

    /** 她睡着没有:睡着的女仆打不开界面。 */
    static boolean asleep(Entity maid) {
        return ((EntityMaid) maid).isSleeping();
    }

    /** 她此刻开着的是不是这只女仆的界面,开着的话是哪一种菜单;没开是 null。 */
    static String showing(ServerPlayer her, Entity maid) {
        if (her.containerMenu instanceof AbstractMaidContainer menu && menu.getMaid() == maid) {
            return BuiltInRegistries.MENU.getKey(menu.getType()).toString();
        }
        return null;
    }

    // ---- 做:和界面上按下那个按钮同一个包 ----

    /** 切工作模式,和任务列表上点一个按钮一样。 */
    static void switchTask(ServerPlayer her, Entity maid, ResourceLocation task) {
        MaidTaskPackage.handle(new MaidTaskPackage(maid.getId(), task), from(her, MaidTaskPackage.TYPE));
    }

    /** 改设置页上的四样,和在设置页上点了"完成"一样:包里是整份设置,没改的照现在的填。 */
    static void configure(ServerPlayer her, Entity maid, Settings wanted) {
        MaidConfigPackage.handle(new MaidConfigPackage(maid.getId(), wanted.home(), wanted.pickup(), wanted.ride(),
                MaidSchedule.valueOf(wanted.schedule().toUpperCase(Locale.ROOT))), from(her, MaidConfigPackage.TYPE));
    }

    /** 打开界面的一页,和点边上的页签一样。 */
    static void open(ServerPlayer her, Entity maid, Tab tab) {
        ToggleTabPackage.handle(new ToggleTabPackage(maid.getId(), tab.index), from(her, ToggleTabPackage.TYPE));
    }

    /** 这个包由她发来时 NeoForge 会交给 {@code handle} 的那个上下文,理由见类注释。 */
    private static IPayloadContext from(ServerPlayer her, CustomPacketPayload.Type<?> type) {
        return new ServerPayloadContext(her.connection, type.id());
    }

    // ---- 她身上的:P 点与女仆数 ----

    /** 每轮身体状态里的那一段:她身上的 P 点,车万女仆给她记的女仆数与上限。 */
    static String bodyState(NumenPlayer her) {
        float power = her.getData(InitDataAttachment.POWER_NUM).get();
        MaidNumAttachment maids = her.getData(InitDataAttachment.MAID_NUM);
        String limit = maids.getMaxNum() == Integer.MAX_VALUE ? "no limit" : "a limit of " + maids.getMaxNum();
        return "<touhou_little_maid>power points " + String.format(Locale.ROOT, "%.2f", power) + " of 5; "
                + maids.get() + " maid(s) counted as yours, " + limit + "</touhou_little_maid>";
    }

    /** 车万女仆给她记的女仆数。 */
    static int counted(Player her) {
        return her.getData(InitDataAttachment.MAID_NUM).get();
    }

    // ---- 车万女仆那边发生的、她该知道的事 ----

    /**
     * 接上车万女仆的两个事件:驯服成功、女仆死后留下墓碑。都挂在最低优先级、不收已取消的——别的模组取消了,这件事就没发生。
     *
     * <p>死亡认的是墓碑那一刻:主人名下的女仆死时,车万女仆把她的东西和她的胶片装进一块墓碑
     * ({@code EntityMaid.dropEquipment}),这个事件带着女仆与墓碑,两样一起报。
     */
    static void listen() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, MaidTamedEvent.class, event -> {
            if (event.getPlayer() instanceof NumenPlayer her) {
                MaidEvents.tamed(her, event.getMaid());
            }
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, MaidTombstoneEvent.class, event -> {
            if (event.getMaid().getOwner() instanceof NumenPlayer her) {
                MaidEvents.died(her, event.getMaid(), event.getTombstone());
            }
        });
    }

    // ---- 写法 ----

    /** 一只女仆在回执与事件里的称呼:编号,起过名字的带上名字。 */
    static String label(Entity maid) {
        return "maid " + maid.getId() + (maid.hasCustomName() ? " (" + maid.getCustomName().getString() + ")" : "");
    }

    static String where(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String schedule(MaidSchedule schedule) {
        return schedule.name().toLowerCase(Locale.ROOT);
    }

    private static double tenth(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
