package com.dwinovo.numen.entity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

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
 */
public final class NumenPlayer extends ServerPlayer {

    private static final String NBT_KEY_OWNER = "NumenOwner";

    /** Owner's player UUID. Null only transiently before the first assignment. */
    private UUID ownerUuid;

    /** Latched once we've handled this body's death, so the post-death routine runs exactly once. */
    private boolean deathHandled;

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


    /** True if {@code item} sits anywhere in the inventory (hotbar/main/offhand all count). */
    public boolean ensureInInventory(Item item) {
        var inv = getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) return true;
        }
        return false;
    }

    /**
     * Hold the item in inventory slot {@code slot} in the main hand the way a real player
     * does — a hotbar slot is simply SELECTED (number-key); a main-inventory slot is SWAPPED
     * into the currently selected hotbar slot (item-conserving). This is the only correct way
     * to "switch to hand": calling {@code setItemInHand(MAIN_HAND, stack)} overwrites the held
     * item (losing it) and aliases ONE {@link net.minecraft.world.item.ItemStack} across two
     * slots, which corrupts the inventory once the stack is consumed. No-op for {@code slot < 0}.
     */
    public void holdInHand(int slot) {
        if (slot < 0) {
            return;
        }
        var inv = getInventory();
        if (net.minecraft.world.entity.player.Inventory.isHotbarSlot(slot)) {
            inv.setSelectedSlot(slot);   // 1.21.5: selected 字段私有化,走读写方法
            return;
        }
        int selected = inv.getSelectedSlot();
        net.minecraft.world.item.ItemStack held = inv.getItem(selected);
        inv.setItem(selected, inv.getItem(slot));
        inv.setItem(slot, held);
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
        if (vehicle instanceof net.minecraft.world.entity.vehicle.AbstractBoat) {
            yRotO = vehicle.getYRot();
            setYRot(vehicle.getYRot());
            setYHeadRot(vehicle.getYRot());
        }
        return true;
    }

    // ---- server tick (restore the movement pass a fake connection skips) ----

    /**
     * Drive the body's own movement physics. A real {@link ServerPlayer} runs
     * {@code travel} (against {@code zza}/{@code xxa}), food, air and pose inside
     * {@link #doTick()}, which the network layer invokes via
     * {@code connection.tick()}. A fake player's connection is a no-op, so
     * {@code doTick()} never fires and the body would only ever turn (a direct
     * {@code setYRot} write) without walking. The entity system already calls
     * {@code super.tick()} (menus / container / position sync), so we add the
     * missing {@code doTick()} movement pass here in our own {@code tick()}
     * override. Every 10 ticks we resync the
     * connection position and let chunk loading follow the body so it never
     * walks out of its loaded area.
     */
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
        if (level() instanceof ServerLevel sl && sl.getGameTime() % 10 == 0) {
            this.connection.resetPosition();
            sl.getChunkSource().move(this);
        }
        try {
            super.tick();
        } catch (RuntimeException ex) {
            reportTickFailure(ex);
        }
        // 摔落结算是玩家<b>唯一</b>由客户端权威的物理:{@code Entity.move} 里那一处被
        // {@code isLocalInstanceAuthoritative()} 挡着(Player.isClientAuthoritative()
        // 恒为 true,服务端算出来就是 false),真正结算的是收到移动包时的
        // {@code doCheckFallDamage}。空壳玩家的连接是空的,那个包永远不来 —— 于是她既
        // 不掉血,{@code fallDistance} 也永远是 0。和上面补 doTick() 是同一件事:
        // 网络层漏掉的那一趟,按原版原样补回来。
        net.minecraft.world.phys.Vec3 before = position();
        try {
            this.doTick();
        } catch (RuntimeException ex) {
            reportTickFailure(ex);
        }
        // 位移只框住上面这一段。召唤、重生、跨维度都发生在 tick 之外,下一刻 before 读到的
        // 已经是新位置,位移天然为零 —— 不需要另写传送豁免。
        net.minecraft.world.phys.Vec3 moved = position().subtract(before);
        doCheckFallDamage(moved.x, moved.y, moved.z, onGround());
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
            output.store(NBT_KEY_OWNER, UUIDUtil.CODEC, ownerUuid);   // 1.21.5 codec 化的 NBT 读写
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag input) {
        super.readAdditionalSaveData(input);
        input.read(NBT_KEY_OWNER, UUIDUtil.CODEC).ifPresent(uuid -> this.ownerUuid = uuid);
    }
}
