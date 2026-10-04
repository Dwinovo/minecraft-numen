package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.EntityInfo;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTamedEvent;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTaskEnableEvent;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTombstoneEvent;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.data.MaidNumAttachment;
import com.github.tartaricacid.touhoulittlemaid.client.resource.pojo.MaidModelInfo;
import com.github.tartaricacid.touhoulittlemaid.crafting.AltarRecipe;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.data.inner.AttackListData;
import com.github.tartaricacid.touhoulittlemaid.entity.info.ServerCustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidConfigManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.PickType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.SchedulePos;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.TabIndex;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.init.InitDataAttachment;
import com.github.tartaricacid.touhoulittlemaid.init.InitRecipes;
import com.github.tartaricacid.touhoulittlemaid.init.InitTaskData;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import com.github.tartaricacid.touhoulittlemaid.network.message.MaidConfigPackage;
import com.github.tartaricacid.touhoulittlemaid.network.message.MaidModelPackage;
import com.github.tartaricacid.touhoulittlemaid.network.message.MaidSubConfigPackage;
import com.github.tartaricacid.touhoulittlemaid.network.message.SendNameTagPackage;
import com.github.tartaricacid.touhoulittlemaid.network.message.SetAttackListPackage;
import com.github.tartaricacid.touhoulittlemaid.network.message.MaidTaskPackage;
import com.github.tartaricacid.touhoulittlemaid.network.message.ToggleTabPackage;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidInfo;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidWorldData;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
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
import java.util.Optional;
import java.util.TreeMap;
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

    /** 车万女仆的名牌界面把名字截到这么长(它的 {@code SendNameTagPackage} 里的同一个数)。 */
    static final int NAME_MAX = 32;

    private Maids() {}

    /** 界面的一页在车万女仆界面边上的页签编号({@link TabIndex})。 */
    private static int tabIndex(MaidApi.Tab tab) {
        return switch (tab) {
            case BACKPACK -> TabIndex.MAIN;
            case BAUBLE -> TabIndex.BAUBLE;
            case CURIOS -> TabIndex.CURIOS;
        };
    }

    /** 一只女仆在设置页上的四样:家模式、拾取、骑乘、日程。日程的三种就是 {@link MaidSchedule} 的名字。 */
    record Settings(boolean home, boolean pickup, boolean ride, MaidApi.Schedule schedule) {}

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
    static List<MaidApi.LastSeen> away(ServerPlayer her) {
        return records(MaidWorldData.get(her.level()).getInfos(her.getUUID()));
    }

    /** 她的女仆死后留下、还没被取空的墓碑,记在同一份存档里。 */
    static List<MaidApi.LastSeen> tombstones(ServerPlayer her) {
        return records(MaidWorldData.get(her.level()).getTombstones(her.getUUID()));
    }

    /** 存档里记着的那几条:名字、位置、维度;这个主人一条都没记过时车万女仆给 null。 */
    private static List<MaidApi.LastSeen> records(List<MaidInfo> infos) {
        List<MaidApi.LastSeen> rows = new ArrayList<>();
        if (infos == null) {
            return rows;
        }
        for (MaidInfo info : infos) {
            rows.add(new MaidApi.LastSeen(info.getName().getString(), info.getChunkPos().immutable(),
                    info.getDimension()));
        }
        return rows;
    }

    /**
     * 一只加载着的女仆此刻的样子:一只实体(主人照 {@code numen.scan.entities} 的说法),加上她的模型、工作、设置与好感等级。
     */
    static MaidApi.Maid row(Entity entity, NumenPlayer her) {
        EntityMaid maid = (EntityMaid) entity;
        boolean here = maid.level() == her.level();
        UUID owner = maid.getOwnerUUID();
        EntityInfo seen = EntityInfo.of(maid).seen(Optional.empty(),
                here ? Optional.of(tenth(her.distanceTo(maid))) : Optional.empty(),
                Optional.of(tenth(maid.getHealth())), Optional.of(tenth(maid.getMaxHealth())),
                owner == null ? Optional.empty() : Optional.of(owner.equals(her.getUUID()) ? "you"
                        : her.isOwnedByPlayer(owner) ? "your owner" : owner(entity)));
        return new MaidApi.Maid(seen, maid.getModelId(), maid.getTask().getUid().toString(),
                schedule(maid.getSchedule()), maid.isHomeModeEnable(), maid.getFavorabilityManager().getLevel(),
                maid.isMaidInSittingPose(),
                here ? Optional.empty() : Optional.of(maid.level().dimension().location().toString()));
    }

    /**
     * 一只女仆的详情({@code tlm.maid.info}):清单里的她({@link #row}),加上设置页的其余几样、好感、背包、日程点与每一个工作模式。
     */
    static MaidApi.Detail detail(Entity entity, NumenPlayer her) {
        EntityMaid maid = (EntityMaid) entity;
        boolean home = maid.isHomeModeEnable();
        SchedulePos points = maid.getSchedulePos();
        return new MaidApi.Detail(row(entity, her), maid.isPickup(), maid.isRideable(), maid.getFavorability(),
                maid.getFavorabilityManager().nextLevelPoint(), maid.getMaidBackpackType().getId().toString(),
                home ? Optional.of(maid.getRestrictCenter().immutable()) : Optional.empty(),
                home ? Optional.of(tenth(maid.getRestrictRadius())) : Optional.empty(),
                points.isConfigured() ? Optional.of(new MaidApi.SchedulePoints(points.getWorkPos().immutable(),
                        points.getIdlePos().immutable(), points.getSleepPos().immutable(),
                        points.getDimension().toString())) : Optional.empty(),
                preferences(maid), equipment(maid), effects(maid), maid.getExperience(), maid.getIsInvulnerable(),
                BuiltInRegistries.ACTIVITY.getKey(maid.getScheduleDetail()).toString(), maid.isSleeping(),
                Optional.ofNullable(maid.getTarget()).map(EntityInfo::of), tasks(maid));
    }

    /** 各装备位上穿着、拿着的;空着的不列。 */
    private static Map<String, MaidApi.Held> equipment(EntityMaid maid) {
        Map<String, MaidApi.Held> worn = new LinkedHashMap<>();
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = maid.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                worn.put(slot.getName(), held(stack));
            }
        }
        return worn;
    }

    private static MaidApi.Held held(ItemStack stack) {
        return new MaidApi.Held(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount());
    }

    private static List<MaidApi.Effect> effects(EntityMaid maid) {
        List<MaidApi.Effect> out = new ArrayList<>();
        for (MobEffectInstance effect : maid.getActiveEffects()) {
            out.add(new MaidApi.Effect(BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString(),
                    effect.getAmplifier(), effect.isInfiniteDuration() ? -1 : effect.getDuration()));
        }
        return out;
    }

    /**
     * 界面任务列表上的每一个工作模式(不含隐藏的),各一项:能不能切过去,以及车万女仆给这个模式列的条件此刻满没满足。
     * "能不能切"问的是车万女仆的任务列表给每个按钮问的同一组问题,见 {@link #switchable}。
     */
    private static List<MaidApi.WorkMode> tasks(EntityMaid maid) {
        List<MaidApi.WorkMode> rows = new ArrayList<>();
        for (IMaidTask task : TaskManager.getNotHiddenTaskList(maid)) {
            List<Pair<String, Predicate<EntityMaid>>> toEnable = new ArrayList<>();
            boolean canSwitch = switchable(task, maid, toEnable);
            List<Pair<String, Predicate<EntityMaid>>> conditions = task.getConditionDescription(maid);
            rows.add(new MaidApi.WorkMode(task.getUid().toString(),
                    task.getUid().equals(maid.getTask().getUid()) ? Optional.of(true) : Optional.empty(), canSwitch,
                    toEnable.isEmpty() ? Optional.empty() : Optional.of(met(toEnable, maid)),
                    conditions.isEmpty() ? Optional.empty() : Optional.of(met(conditions, maid))));
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

    /** 设置页「女仆配置」那一页上的八样。 */
    static MaidApi.Preferences preferences(Entity entity) {
        MaidConfigManager.SyncNetwork now = ((EntityMaid) entity).getConfigManager().getSyncNetwork();
        return new MaidApi.Preferences(now.showBackpack(), now.showBackItem(), now.showChatBubble(),
                Math.round(now.soundFreq() * 100) / 100.0, pickupKind(now.pickType()), now.openDoor(),
                now.openFenceGate(), now.activeClimbing());
    }

    /** 她的攻击名单:只有被指定过态度的实体种类;别的种类由车万女仆按默认判。 */
    static MaidApi.Aims aims(Entity entity) {
        AttackListData list = ((EntityMaid) entity).getData(InitTaskData.ATTACK_LIST);
        Map<String, MaidApi.Stance> stances = new TreeMap<>();
        if (list != null) {
            list.attackGroups().forEach((type, stance) -> stances.put(type.toString(), stance(stance)));
        }
        return new MaidApi.Aims(entity.getId(), stances);
    }

    /** 写进名单的实体种类:得是注册过的(攻击名单界面添加时也这样查)。 */
    static String entityType(String id) {
        ResourceLocation type = ResourceLocation.tryParse(id);
        if (type == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(type)) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, id + " is not an entity type", null);
        }
        return type.toString();
    }

    /** 她穿的模型。 */
    static String model(Entity maid) {
        return ((EntityMaid) maid).getModelId();
    }

    /** 这个服务器登记的这个模型的元信息:显示名、介绍。 */
    static Optional<MaidModelInfo> modelInfo(String model) {
        return ServerCustomPackLoader.SERVER_MAID_MODELS.getInfo(model);
    }

    /** 这个服务器的车万女仆装没装这个模型。 */
    static boolean hasModel(String model) {
        return ServerCustomPackLoader.SERVER_MAID_MODELS.containsInfo(model);
    }

    /** 她主手里的名牌有几块:车万女仆只在主手拿着名牌时给女仆改名,改完拿走一块。 */
    static int nameTags(Player her) {
        ItemStack hand = her.getMainHandItem();
        return hand.is(Items.NAME_TAG) ? hand.getCount() : 0;
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
                MaidSchedule.valueOf(wanted.schedule().name())), from(her, MaidConfigPackage.TYPE));
    }

    /** 改「女仆配置」那一页上的八样,和在那一页上点按钮一样:包里是整份,没改的照现在的填。 */
    static void configure(ServerPlayer her, Entity maid, MaidApi.Preferences wanted) {
        MaidSubConfigPackage.handle(new MaidSubConfigPackage(maid.getId(), new MaidConfigManager.SyncNetwork(
                wanted.showBackpack(), wanted.showBackItem(), wanted.chatBubble(), (float) wanted.soundFrequency(),
                pickType(wanted.pickupKind()), wanted.openDoor(), wanted.openFenceGate(), wanted.activeClimbing())),
                from(her, MaidSubConfigPackage.TYPE));
    }

    /** 写攻击名单,和攻击模式的设置页关闭时发的包一样:整份名单。 */
    static void aim(ServerPlayer her, Entity maid, Map<String, MaidApi.Stance> stances) {
        Map<ResourceLocation, MonsterType> groups = new java.util.HashMap<>();
        stances.forEach((type, stance) -> groups.put(ResourceLocation.parse(type), monsterType(stance)));
        SetAttackListPackage.handle(new SetAttackListPackage(maid.getId(), groups),
                from(her, SetAttackListPackage.TYPE));
    }

    /** 改名,和在名牌界面里点完成一样。 */
    static void name(ServerPlayer her, Entity maid, String name, boolean alwaysShow) {
        SendNameTagPackage.handle(new SendNameTagPackage(maid.getId(), name, alwaysShow),
                from(her, SendNameTagPackage.TYPE));
    }

    /** 换模型,和在女仆的模型界面里选一个一样。 */
    static void model(ServerPlayer her, Entity maid, String model) {
        MaidModelPackage.handle(new MaidModelPackage(maid.getId(), ResourceLocation.parse(model)),
                from(her, MaidModelPackage.TYPE));
    }

    /** 打开界面的一页,和点边上的页签一样。 */
    static void open(ServerPlayer her, Entity maid, MaidApi.Tab tab) {
        ToggleTabPackage.handle(new ToggleTabPackage(maid.getId(), tabIndex(tab)), from(her, ToggleTabPackage.TYPE));
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

    // ---- 祭坛 ----

    /** 祭坛的每一条配方:产物、需要的材料、要的 P 点。 */
    static List<AltarApi.Recipe> altarRecipes(NumenPlayer her) {
        List<AltarApi.Recipe> out = new ArrayList<>();
        for (RecipeHolder<AltarRecipe> holder : her.serverLevel().getRecipeManager()
                .getAllRecipesFor(InitRecipes.ALTAR_CRAFTING.get())) {
            AltarRecipe recipe = holder.value();
            List<AltarApi.Need> needs = new ArrayList<>();
            for (Ingredient ingredient : recipe.getIngredients()) {
                List<String> items = new ArrayList<>();
                for (ItemStack stack : ingredient.getItems()) {
                    items.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                }
                needs.add(new AltarApi.Need(items));
            }
            boolean item = recipe.isItemCraft();
            out.add(new AltarApi.Recipe(holder.id().toString(),
                    item ? Optional.of(held(recipe.getResult())) : Optional.empty(),
                    item ? Optional.empty() : Optional.of(recipe.getEntityType().toString()),
                    needs, tenth(recipe.getPower())));
        }
        return out;
    }

    // ---- 写法 ----

    /** 一只女仆在回执与事件里的称呼:编号,起过名字的带上名字。 */
    static String label(Entity maid) {
        return "maid " + maid.getId() + (maid.hasCustomName() ? " (" + maid.getCustomName().getString() + ")" : "");
    }

    static String where(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static MaidApi.PickupKind pickupKind(PickType type) {
        return switch (type) {
            case ONLY_ITEM -> MaidApi.PickupKind.ITEM;
            case ONLY_XP -> MaidApi.PickupKind.XP;
            case ALL -> MaidApi.PickupKind.ALL;
        };
    }

    private static PickType pickType(MaidApi.PickupKind kind) {
        return switch (kind) {
            case ITEM -> PickType.ONLY_ITEM;
            case XP -> PickType.ONLY_XP;
            case ALL -> PickType.ALL;
        };
    }

    private static MaidApi.Stance stance(MonsterType type) {
        return MaidApi.Stance.valueOf(type.name());
    }

    private static MonsterType monsterType(MaidApi.Stance stance) {
        return MonsterType.valueOf(stance.name());
    }

    private static MaidApi.Schedule schedule(MaidSchedule schedule) {
        return MaidApi.Schedule.valueOf(schedule.name());
    }

    private static double tenth(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
