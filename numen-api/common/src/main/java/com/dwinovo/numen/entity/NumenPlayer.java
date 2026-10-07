package com.dwinovo.numen.entity;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.pathing.body.Body;
import com.dwinovo.numen.pathing.body.Controls;
import com.dwinovo.numen.pathing.body.Physics;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * The companion body: a server-side fake {@link ServerPlayer}. Replaces the old
 * custom {@code NumenEntity} Mob so the companion is a first-class player —
 * native interaction/combat code paths (universal mod compatibility), its own
 * player inventory, and free chunk loading + playerdata persistence by virtue of
 * being a list-resident player.
 *
 * <h2>Identity &amp; ownership</h2>
 * Created by {@link CompanionFactory} with a stable per-companion UUID (carried
 * in the {@link GameProfile}); the enumerable index lives in
 * {@link CompanionRegistry}. Unlike the Mob, a fake player cannot carry custom
 * {@code SynchedEntityData}, so the owner is a plain server-side field persisted
 * to the companion's own playerdata {@code .dat} via
 * {@link #addAdditionalSaveData}. Owner checks are UUID comparisons — never
 * vanilla {@code isOwnedBy} (which resolves through a level and breaks across
 * dimensions).
 *
 * <h2>身体</h2>
 * 她就是寻路模块的身体端口({@link Body}):一副键盘({@link Controls}),导航、本能与各件活按的都是它;每刻在自己的实体刻里
 * 跑一次物理步进({@link Physics#step}),按着的键在那里落成输入。
 */
public final class NumenPlayer extends ServerPlayer implements Body {

    private static final String NBT_KEY_OWNER = "NumenOwner";

    /** Owner's player UUID. Null only transiently before the first assignment. */
    private UUID ownerUuid;

    /** Latched once we've handled this body's death, so the post-death routine runs exactly once. */
    private boolean deathHandled;

    /** 她没有客户端,服务端等的那几个回执由它代答。见 {@link FakeClient}。 */
    private final FakeClient fakeClient = new FakeClient(this);

    /** 她的键盘:谁要让身体走、跳、潜行都按它,{@link Physics#step} 每刻落一次。 */
    private final Controls controls = new Controls();

    /**
     * 死因,在 {@link #die} 里趁早抄下来。
     *
     * <p>不能等到 {@link #tick} 里再问战斗记录:原版 {@code ServerPlayer.die()} 的<b>最后一行</b>
     * 是 {@code getCombatTracker().recheckStatus()},玩家已死就把记录清空。我们的死亡检测是
     * tick 轮询,跑到的时候记录早没了,{@code getDeathMessage()} 只能返回兜底的
     * {@code death.attack.generic}——"她死了",没有凶手。于是原版聊天里广播的是
     * "被僵尸杀死了",她自己却只知道"我死了"。
     */
    private String deathMessage;

    /**
     * 上一刻她在不在床上,{@link #pollWokeUp} 用它比出"刚醒"这一刻。
     *
     * <p><b>跟着身体走,不进静态表</b>:她休眠再回来是一具新身体,这一位天然是 false,
     * 于是不会诈出一条"你醒了";记在按 UUID 索引的表里就得另配一套离场清理。
     */
    private boolean sleepingLastTick;

    /**
     * 已经为这一轮饥饿说过了。<b>饱食是持续状态,每刻都成立</b> —— 不去抖的话一条 urgent
     * 会变成一串,把主人吵死(逃跑那次实测二十秒发了十七条)。回到 {@link #FED_LEVEL}
     * 以上才重新武装。
     */
    private boolean hungerReported;

    /** 饿到这个程度就说一声。原版低于 6 跑不动,低于 18 自然回血停。 */
    private static final int HUNGRY_LEVEL = 6;

    /** 回到这个程度才重新武装 —— 留一大段迟滞,免得在阈值上一条接一条。 */
    private static final int FED_LEVEL = 14;

    /**
     * 她这一刻<b>主动按住</b>的本能(按 {@code Reflex.id()})。
     *
     * <p>一件正在做的事若自己就会处理某条本能管的局面,就把那条按住,别让两边为同一件事抢
     * 身体——{@code attack} 任务按住 {@code mob_defense},因为它的判据比本能细(认得爬行者
     * 该退多远、够不着该换弓),而本能抢过去只会让它拉到一半的弓作废。
     *
     * <p><b>按住的是"哪条本能",不是"哪些目标"。</b>按目标记的话,一群会分裂的史莱姆裂开
     * 之后那份 id 清单当场作废。
     *
     * <p>跟着身体走,休眠回来天然是空的;{@code CompanionBrain} 在她闲下来时统一解除,
     * 所以调用方不必显式还。
     */
    private java.util.Set<String> pausedReflexes = java.util.Set.of();

    public NumenPlayer(MinecraftServer server, ServerLevel level, GameProfile profile,
                        ClientInformation clientInformation) {
        super(server, level, profile, clientInformation);
    }

    /**
     * 点亮全部皮肤覆盖层(帽子/夹克/左右袖/左右裤腿)与披风。假玩家没有客户端上报的
     * 模型定制,不设这个字节客户端只渲染单层基础皮肤。该字节是同步实体数据、不随 .dat
     * 存取,故每次进世界都要重设一次(经 {@code protected} 的 DATA_PLAYER_MODE_CUSTOMISATION
     * 访问,子类内可见)。
     */
    public void showAllSkinLayers() {
        getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f);
    }

    /**
     * 她是不是<b>刚好在这一刻</b>从床上醒了。每服务端 tick 问一次(见 {@code CompanionTickDispatcher})。
     *
     * <p>为什么是轮询而不是挂钩子:两个加载器各有自己的"停止睡眠"事件,接起来是两份平台代码;
     * 而这里要比的只有一位布尔,每 tick 一次读取在 50ms 的预算里看不见。
     *
     * <p>死着的时候不算醒——原版 {@code LivingEntity.die} 会先把睡眠停掉,不挡的话她每次
     * 死在床上都会多出一条"你醒了"贴在死亡事件旁边。
     */
    /**
     * 这一刻该不该跟主人说"我饿了"。<b>一轮饥饿只说一次</b>:说过就闭嘴,吃回
     * {@link #FED_LEVEL} 以上才重新武装。
     *
     * <p>她<b>不会自己吃</b> —— 那条常驻链删了。饿了是主人该知道的事,交互本身就是目的;
     * 而"我解决不了"才值得打断他,这跟逃跑那条一个道理:打赢了不吵他。
     */
    public boolean pollGotHungry() {
        int food = getFoodData().getFoodLevel();
        if (food >= FED_LEVEL) {
            hungerReported = false;
            return false;
        }
        if (food > HUNGRY_LEVEL || hungerReported || !isAlive()) {
            return false;
        }
        hungerReported = true;
        return true;
    }

    /**
     * 这一轮"背包满了"已经说过了。背包满是持续状态,掉落物每刻都在碰她——不去抖就是每刻一条;背包里又有空格了才重新武装。
     */
    private boolean fullReported;

    /** 因背包放不下而留在地上、还没说出去的那一件;没有是 null。 */
    private LeftBehind leftBehind;

    /** 一件因背包放不下而留在地上的东西:是什么、几个、在哪一格。 */
    public record LeftBehind(ItemStack stack, BlockPos pos) {}

    /**
     * 一件掉落物碰到了她,原版正要往背包里放(由 {@code ItemEntityTouchMixin} 交来)。放不放得下照原版背包找格子的两步:有一格还能
     * 叠上它({@code getSlotWithRemainingSpace}),或者有空格({@code getFreeSlot});创造模式什么都收。放不下的记下来,由
     * {@link #pollInventoryFull} 交出去。"满了"只在这里判:挖、捡、合成这些活不各自判。
     */
    public void touchedItem(ItemEntity item) {
        if (fullReported || leftBehind != null || hasInfiniteMaterials()) {
            return;
        }
        Inventory inventory = getInventory();
        ItemStack stack = item.getItem();
        if (inventory.getSlotWithRemainingSpace(stack) != -1 || inventory.getFreeSlot() != -1) {
            return;
        }
        leftBehind = new LeftBehind(stack.copy(), item.blockPosition());
    }

    /**
     * 这一刻该不该跟主人说"背包满了、东西留在了地上"。每服务端 tick 问一次(见 {@code CompanionTickDispatcher})。<b>一轮只说
     * 一次</b>,复位见 {@link #rearmInventoryFull}。
     */
    public LeftBehind pollInventoryFull() {
        if (leftBehind == null) {
            return null;
        }
        LeftBehind told = leftBehind;
        leftBehind = null;
        fullReported = true;
        return told;
    }

    /**
     * 背包里又有了空格就重新武装:有空格就什么都放得下,下一回再满、再有东西放不下时再说;还没说出去的那一件也作废。在自己的
     * 实体刻里、捡东西之前看——腾出的那一格要是这一刻就被捡起的东西占上,事后再看就看不见它空过。
     */
    private void rearmInventoryFull() {
        if (getInventory().getFreeSlot() != -1) {
            fullReported = false;
            leftBehind = null;
        }
    }

    /** 一件在她身上用坏的装备:是什么、坏在哪个装备位。 */
    public record BrokenGear(Item item, EquipmentSlot slot) {}

    /** 最近用坏的装备,按先后;只留最近 {@link #BROKEN_KEPT} 件。 */
    private final java.util.ArrayList<BrokenGear> brokenGear = new java.util.ArrayList<>();
    /** 到现在一共用坏过几件:{@link #brokenGearMark} 读它,{@link #brokenGearSince} 拿它算新坏的是哪几件。 */
    private long brokenGearTotal;
    private static final int BROKEN_KEPT = 16;

    /**
     * 原版装备耐久耗尽、碎掉的那一刻(挖掘、打击、盾挡、鞘翅……都经 {@code ItemStack.hurtAndBreak} 到这里)。记下来,
     * 干活的人事后用 {@link BodyDelta} 说给她听;这里只记流水,不替谁下结论。
     */
    @Override
    public void onEquippedItemBroken(Item item, EquipmentSlot slot) {
        super.onEquippedItemBroken(item, slot);
        if (brokenGear.size() == BROKEN_KEPT) {
            brokenGear.remove(0);
        }
        brokenGear.add(new BrokenGear(item, slot));
        brokenGearTotal++;
    }

    /** 此刻用坏过几件的读数;之后问 {@link #brokenGearSince} 就是这个读数以来新坏的。 */
    public long brokenGearMark() {
        return brokenGearTotal;
    }

    /** 读数 {@code mark} 以来用坏的装备,按先后。 */
    public List<BrokenGear> brokenGearSince(long mark) {
        int fresh = (int) Math.min(brokenGearTotal - mark, brokenGear.size());
        return List.copyOf(brokenGear.subList(brokenGear.size() - fresh, brokenGear.size()));
    }

    /** 主人血量的看护(纯判定在 {@link OwnerHurtWatch},便于无头单测)。 */
    private final OwnerHurtWatch ownerWatch = new OwnerHurtWatch();

    /** 一次"主人挨打"报告:攻击者、血量与档位(urgent = 跌进危险区)。 */
    public record OwnerHurt(String attacker, float hp, float maxHp, boolean urgent) {}

    /**
     * 主人这一刻有没有挨打值得说。每服务端 tick 问一次(见 {@code CompanionTickDispatcher});
     * 主人不在线传 null,基线即重置。只报实体攻击,分档与去抖全在 {@link OwnerHurtWatch}。
     */
    public OwnerHurt pollOwnerHurt(ServerPlayer owner, long now) {
        if (owner == null) {
            ownerWatch.reset();
            return null;
        }
        net.minecraft.world.damagesource.DamageSource src = owner.getLastDamageSource();
        net.minecraft.world.entity.Entity attacker = src == null ? null : src.getEntity();
        OwnerHurtWatch.Verdict verdict = ownerWatch.poll(
                owner.getUUID(), owner.getHealth(), attacker != null, now);
        if (verdict == OwnerHurtWatch.Verdict.NONE) {
            return null;
        }
        String label = attacker instanceof net.minecraft.world.entity.player.Player p
                ? p.getGameProfile().getName()
                : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(attacker.getType()).getPath();
        return new OwnerHurt(label, owner.getHealth(), owner.getMaxHealth(),
                verdict == OwnerHurtWatch.Verdict.DANGER);
    }

    public boolean pollWokeUp() {
        if (!isAlive()) {
            sleepingLastTick = false;
            return false;
        }
        boolean now = isSleeping();
        boolean woke = sleepingLastTick && !now;
        sleepingLastTick = now;
        return woke;
    }

    /** 按住一条本能。见 {@link #pausedReflexes}。 */
    public void pauseReflex(String reflexId) {
        if (pausedReflexes.contains(reflexId)) {
            return;
        }
        java.util.Set<String> next = new java.util.HashSet<>(pausedReflexes);
        next.add(reflexId);
        pausedReflexes = java.util.Set.copyOf(next);
    }

    /** 这条本能这一刻被按住了吗。 */
    public boolean reflexPaused(String reflexId) {
        return pausedReflexes.contains(reflexId);
    }

    /** 她闲下来了,全部解除——按住是临时的,不必谁去显式还。 */
    public void resumeAllReflexes() {
        pausedReflexes = java.util.Set.of();
    }

    /**
     * 挂在这具身体上的同伴级状态,按类型各一份(征询登记处、等主人答复的调用之类)。
     *
     * <p>与 {@link #pausedReflexes} 同一原则——<b>跟着身体走,不进静态表</b>:身体没了状态
     * 就没了,休眠回来是新身体、新状态,不用给每一种状态各配一套离场清理;引擎不认识
     * 内容包的类型,所以按类型取、首次取时由调用方建。
     */
    private final java.util.Map<Class<?>, Object> bodyState = new java.util.HashMap<>();

    /** 取(首次取时建)这具身体上的一份同伴级状态。 */
    public <T> T state(Class<T> type, java.util.function.Supplier<T> init) {
        return type.cast(bodyState.computeIfAbsent(type, k -> init.get()));
    }

    /** The loaded companion body with this UUID, or {@code null} if not spawned. */
    public static NumenPlayer findByUuid(MinecraftServer server, UUID uuid) {
        return server.getPlayerList().getPlayer(uuid) instanceof NumenPlayer ap ? ap : null;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    /** Cross-dimension safe owner check — UUID comparison, not level-scoped lookup. */
    public boolean isOwnedByPlayer(UUID playerUuid) {
        return ownerUuid != null && ownerUuid.equals(playerUuid);
    }

    /** The owner as an online player, server-wide; null when offline. */
    public ServerPlayer resolveOwnerPlayer() {
        return ownerUuid == null ? null : level().getServer().getPlayerList().getPlayer(ownerUuid);
    }

    /**
     * The owner's name for people to read ({@link #playerName}); empty when there is no owner or the name is unknown.
     */
    public String ownerName() {
        return ownerUuid == null ? "" : playerName(getServer(), ownerUuid);
    }

    /**
     * A player's name for people to read: the online player's (a companion is one too), else the server's profile
     * cache; empty when the name is unknown.
     */
    public static String playerName(net.minecraft.server.MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        return online != null ? online.getGameProfile().getName()
                : java.util.Optional.ofNullable(server.getProfileCache())
                        .flatMap(cache -> cache.get(player))
                        .map(com.mojang.authlib.GameProfile::getName)
                        .orElse("");
    }


    /** True if {@code item} sits anywhere in the inventory (hotbar/main/offhand all count). */
    public boolean ensureInInventory(Item item) {
        var inv = getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) return true;
        }
        return false;
    }

    /**
     * 上船那一刻把身体朝向对齐船头。真客户端在 {@code handleSetEntityPassengersPacket}
     * 里做这件事,服务端身体没有那个包——不补的话她背对船头坐下,而船的转向又从
     * 她"现在朝哪"没有任何约束,画面立刻穿帮。与 Carpet 假玩家同一处理。
     */
    @Override
    public boolean startRiding(net.minecraft.world.entity.Entity vehicle, boolean force) {
        if (!super.startRiding(vehicle, force)) {
            return false;
        }
        if (vehicle instanceof net.minecraft.world.entity.vehicle.Boat) {
            yRotO = vehicle.getYRot();
            setYRot(vehicle.getYRot());
            setYHeadRot(vehicle.getYRot());
        }
        return true;
    }

    /**
     * 挨打。原样交给父类结算,只在真的掉了血之后广播一条 {@code HURT}。
     *
     * <p>发在这里而不是 tick 里扫 {@code hurtTime}:扫的话拿不到来源和伤害量
     * (下一次伤害会盖掉 {@code getLastDamageSource}),而监听者要靠这两样分情况。
     * 返回 false 表示这次伤害被挡下/免疫了,那不是"受伤",不发。
     */
    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        boolean took = super.hurt(source, amount);
        if (took) {
            CompanionEvents.fire(CompanionEvent.HURT,
                    new CompanionEvent.Hurt(this, source, amount));
        }
        return took;
    }

    /**
     * 死因在这里抄下来——原版广播死亡消息也是在这一刻(清空战斗记录之前)。
     * 抄的是同一句话,所以她知道的和聊天里广播的一字不差。
     */
    @Override
    public void die(net.minecraft.world.damagesource.DamageSource cause) {
        this.deathMessage = getCombatTracker().getDeathMessage().getString();
        super.die(cause);
    }

    /** 上一次的死因(原版死亡消息原文);还没死过则 null。 */
    public String deathMessage() {
        return deathMessage;
    }

    /** 代她答话的那一半(她没有客户端);下行包由 {@code MixinServerCommonPacketListener} 交到这里。 */
    @com.dwinovo.numen.api.Internal
    public FakeClient fakeClient() {
        return fakeClient;
    }

    // ---- 身体端口 ----

    @Override
    public ServerPlayer entity() {
        return this;
    }

    @Override
    public Controls controls() {
        return controls;
    }

    @Override
    public void tick() {
        // A fake player isn't auto-removed on death (no client to send a respawn packet), so it would
        // sit at 0 HP forever. Detect death once, hand off to the recoverable-death routine (stop the
        // brain, schedule a respawn at the owner), and skip the normal movement/AI tick for this corpse.
        if (!deathHandled && (getHealth() <= 0.0f || isDeadOrDying())) {
            deathHandled = true;
            Companions.onDeath(this);
            return;
        }
        rearmInventoryFull();
        try {
            super.tick();
        } catch (RuntimeException ex) {
            reportTickFailure(ex);
        }
        // 她没有客户端:按着的键落成输入、玩家自己的一刻、摔伤结算、移动统计、区块跟随,原版由客户端与网络层替真玩家
        // 做的这一趟,由寻路模块的物理步进在这里补上,每刻一次
        try {
            Physics.step(this);
        } catch (RuntimeException ex) {
            reportTickFailure(ex);
        }
    }

    /** 同一具身体只吵一次:tick 每秒二十下,真炸起来就是每秒二十条,日志立刻没法看。 */
    private boolean tickFailureLogged;

    /**
     * 这一 tick 炸了:咽下去,记一次。
     *
     * <h2>为什么不能让它冒上去</h2>
     * 异常冒到 {@code ServerLevel} 的实体 tick 循环,原版会包成 {@code ReportedException} 掀掉
     * 整个服务端主循环——看门狗六十秒后判定崩溃强制关服,一屋子玩家一起掉线。
     *
     * <h2>最常见的抛出方不是我们</h2>
     * 同伴以"玩家"身份待在玩家列表里,别的模组收到它的生命周期事件时,没想过这个"玩家"没有
     * 真实连接。这一类我们永远堵不完(堵掉一个具体原因,下一个模组会拿别的东西),只能不让
     * 它掀桌子。
     *
     * <p>代价完全不对等:咽下去是这一 tick 白跑;不咽是整台服务器没了。
     */
    private void reportTickFailure(RuntimeException ex) {
        if (tickFailureLogged) {
            return;
        }
        tickFailureLogged = true;
        com.dwinovo.numen.Constants.LOG.error(
                "[numen] 同伴 {} 的 tick 抛了异常,这一 tick 跳过(之后不再重复记这一条)",
                getUUID(), ex);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag output) {
        super.addAdditionalSaveData(output);
        if (ownerUuid != null) {
            output.putUUID(NBT_KEY_OWNER, ownerUuid);   // 1.21.4: no CompoundTag.store(Codec)
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag input) {
        super.readAdditionalSaveData(input);
        if (input.hasUUID(NBT_KEY_OWNER)) this.ownerUuid = input.getUUID(NBT_KEY_OWNER);
    }
}
