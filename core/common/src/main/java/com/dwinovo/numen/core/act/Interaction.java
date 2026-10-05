package com.dwinovo.numen.core.act;

import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.nav.CompanionHands;
import com.dwinovo.numen.pathing.body.Crosshair;
import com.dwinovo.numen.pathing.body.Effector;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Verdict;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The most-native interaction primitive for a fake-player body:
 * aim the eyes at a target, then "press"
 * one mouse button (left = ATTACK, right = USE) with a {@link Timing}. Every
 * higher-level action is a thin layer on top: mining = ATTACK a block (hold),
 * {@code attack} = ATTACK an entity, eat/bow = hold USE in the air.
 *
 * <h2>Native dispatch (the same server entry points a real client's packets reach)</h2>
 * <ul>
 *   <li>ATTACK + block  → the body's own hands ({@link CompanionHands}: the vanilla dig loop behind the permission
 *       layer; creative insta / survival timed) on the block the crosshair lands on, with whatever is held</li>
 *   <li>ATTACK + entity → {@code player.attack} (cooldown-scaled damage / sweep / knockback)</li>
 *   <li>USE + block     → {@code gameMode.useItemOn} (vanilla place / activate), both hands tried</li>
 *   <li>USE + entity    → {@code entity.interact} then {@code player.interactOn} (trade / breed / mount), both hands</li>
 *   <li>USE + air       → {@code gameMode.useItem} (+ a hold for food / bow)</li>
 * </ul>
 *
 * <p>右键方块总是按一个已经解析好的命中按:准星落点({@link #forHit},准星由寻路模块的
 * {@code Crosshair} 拾取),或调用方指定的那一面({@link #useBlock}),这里不另打射线。
 * 准星语义的 USE 另有一步收尾:方块/实体没吃掉点击时落到物品自用——真客户端的完整右键
 * 顺序,见 {@link #fallthroughUse}。指定命中面的外科原语没有这一步。
 *
 * <h2>Timing</h2>
 * {@link Timing#once()} taps once; {@link Timing#repeat} taps N times spaced by an
 * interval (auto-click a button, grind a mob); {@link Timing#hold()} holds the
 * button until the action self-completes (a block breaks, food finishes);
 * {@link Timing#hold(int)} holds up to N ticks then releases (draw + loose a bow).
 *
 * <p>Stateful + ticked (like {@link BlockDigger} / {@code PlayerNav}). The caller
 * walks the body within reach first; this only aims and presses.
 */
public final class Interaction {

    public enum Status { RUNNING, DONE, FAILED }
    public enum Button { ATTACK, USE }

    /** The two hands USE tries, main first (vanilla interaction tries both). */
    private static final InteractionHand[] HANDS = {InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND};

    /**
     * When and how often the button fires
     * (once / continuous / interval). {@code hold} actions press-and-hold until
     * the action finishes on its own (breaking, eating) or {@code maxHold} elapses
     * (bow); discrete actions fire {@code limit} times spaced by {@code interval}.
     */
    public static final class Timing {
        final boolean hold;
        final int limit;     // discrete fires (>=1); ignored for hold
        final int interval;  // ticks between discrete fires (>=1)
        final int maxHold;   // hold: release after this many ticks; 0 = until self-complete

        private Timing(boolean hold, int limit, int interval, int maxHold) {
            this.hold = hold;
            this.limit = limit;
            this.interval = interval;
            this.maxHold = maxHold;
        }

        /** One single press. */
        public static Timing once() {
            return new Timing(false, 1, 1, 0);
        }

        /** {@code times} presses, each spaced {@code interval} ticks apart. */
        public static Timing repeat(int times, int interval) {
            return new Timing(false, Math.max(1, times), Math.max(1, interval), 0);
        }

        /** Hold until the action finishes on its own (block broken / food eaten). */
        public static Timing hold() {
            return new Timing(true, -1, 1, 0);
        }

        /** Hold up to {@code maxTicks}, then release (e.g. draw a bow and loose). */
        public static Timing hold(int maxTicks) {
            return new Timing(true, -1, 1, Math.max(1, maxTicks));
        }
    }

    private final NumenPlayer player;
    private final Button button;
    private final BlockPos block;     // non-null → block target
    private final Entity entity;      // non-null → entity target
    private final InteractionHand hand;
    private final Timing timing;

    private BlockHitResult presetHit; // the block hit already resolved (crosshair, or the face the caller chose)
    /**
     * 准星语义的 USE 才有的兜底:方块/实体没吃掉点击时,同一次按键落到物品自用
     * ({@code gameMode.useItem})——真客户端就是这个顺序(useItemOn 不消费就发
     * ServerboundUseItemPacket),桶找水、船找水面、掷物出手都住在那条路上。
     * 外科原语(指定命中面的放置、开台)不设兜底:那里落空就该落空,兜底会把
     * 手里的东西扔出去。false = 兜底关闭或被任务层否决(身体约束物品)。
     */
    private boolean itemFallthrough;
    /** 按住潜行再点({@code --sneak}),见 {@link #crouched}。 */
    private boolean sneak;
    /** 上一刻服务端就已经看到她按着潜行({@code isShiftKeyDown}),这一刻姿态也跟上了。 */
    private boolean crouchSettled;
    private int fires;
    private int cooldown;             // ticks until the next discrete press
    private boolean started;          // USE+air: the hold has begun
    private int held;                 // USE+air: ticks held so far
    private boolean hardFail;         // a fire hit an unrecoverable error
    private String failReason = "interaction failed";
    private FailureType failType = FailureType.UNKNOWN;
    private String lastUseOutcome = "not fired";

    private Interaction(NumenPlayer player, Button button, BlockPos block, Entity entity,
                        InteractionHand hand, Timing timing) {
        this.player = player;
        this.button = button;
        this.block = block == null ? null : block.immutable();
        this.entity = entity;
        this.hand = hand;
        this.timing = timing;
    }

    // ---- factories (default timings; overloads take an explicit Timing) ----

    /**
     * 左键按在准星落着的那一格上({@code hit}),按住直到它碎:手上是什么就用什么,不换工具、不挪步、不清别的格——一次纯按键。
     * 创造一下就碎,生存按手上的东西算时间,都是原版的手自己分。
     */
    public static Interaction attackBlock(NumenPlayer p, BlockHitResult hit, boolean hold) {
        Interaction i = new Interaction(p, Button.ATTACK, hit.getBlockPos(), null, InteractionHand.MAIN_HAND,
                hold ? Timing.hold() : Timing.once());
        i.presetHit = hit;
        return i;
    }

    /** Left-click an entity once (cooldown-gated native attack). */
    public static Interaction attackEntity(NumenPlayer p, Entity target) {
        return attackEntity(p, target, Timing.once());
    }

    public static Interaction attackEntity(NumenPlayer p, Entity target, Timing timing) {
        return new Interaction(p, Button.ATTACK, null, target, InteractionHand.MAIN_HAND, timing);
    }

    /** Right-click a pre-resolved block hit — placement / precise activation supplies
     *  the exact support face, and this presses against {@code hit}. */
    public static Interaction useBlock(NumenPlayer p, BlockHitResult hit, InteractionHand hand) {
        Interaction i = new Interaction(p, Button.USE, hit.getBlockPos(), null, hand, Timing.once());
        i.presetHit = hit;
        return i;
    }

    /**
     * 右键落在 {@code hit} 这一面、手里是 {@code stack} 时,会不会往世界里放东西、放在哪:方块物品贴着
     * 命中面放进可替换的格,桶倒出或舀起液体,打火石与火焰弹点起火——都是一次放置,交权限层裁决。
     * 不往世界里放东西时为 null。按下右键的各处(导航执行、use block)都按这一份判。
     */
    public static Action placementOf(Level level, BlockHitResult hit, ItemStack stack) {
        BlockPos placeAt = hit.getBlockPos().relative(hit.getDirection());
        BlockState before = level.getBlockState(placeAt);
        Item item = stack.getItem();
        boolean places = (before.canBeReplaced() && item instanceof BlockItem)
                || item instanceof BucketItem
                || item instanceof FlintAndSteelItem
                || item instanceof FireChargeItem;
        return places ? Action.place(placeAt, before, item) : null;
    }

    /** Right-click in the air with the held item, on the given {@link Timing}
     *  ({@code hold()} eats food / {@code hold(n)} draws and looses a bow). */
    public static Interaction useInAir(NumenPlayer p, InteractionHand hand, Timing timing) {
        return new Interaction(p, Button.USE, null, null, hand, timing);
    }

    /** vanilla {@code Minecraft.rightClickDelay} — held right-click re-fires this often. */
    private static final int RIGHT_CLICK_DELAY = 4;
    /** A "hold forever" fire count; the owning task stops us after hold_ticks / on completion. */
    private static final int CONTINUOUS = 1_000_000;

    /**
     * Build the native action for a resolved crosshair {@code hit} + {@code button}, mapping
     * {@code holdTicks} to the cell's natural cadence — a 6-cell (button × target) dispatch:
     * <ul>
     *   <li>ATTACK·BLOCK → hit it (tap = one press, which breaks only what breaks at once; hold = till the block is
     *       gone);</li>
     *   <li>ATTACK·ENTITY → hit (tap = one cooldown-gated hit; hold = keep hitting);</li>
     *   <li>USE·BLOCK → activate (tap once; hold re-clicks every rightClickDelay — modded crank);</li>
     *   <li>USE·ENTITY → interact (tap once; hold re-clicks);</li>
     *   <li>USE·AIR → useItem (tap = throw; hold = charge/eat up to ticks, or self-complete);</li>
     *   <li>ATTACK·AIR → {@code null} (left-click air does nothing).</li>
     * </ul>
     * {@code holdTicks}: 0 = tap, &gt;0 / -1 = hold. The block/entity hit is used as-is (the
     * native raytrace already resolved the exact face/point — no re-raycast). The caller drives
     * the returned object to completion and enforces the hold duration.
     *
     * @param itemFallthrough USE 的准星兜底开关(见 {@link #itemFallthrough}):方块/实体
     *                        没吃掉点击就落到物品自用。任务层拿它挡身体约束物品——
     *                        手里是食物/末影珍珠时传 false,免得点了块石头把自己喂了。
     * @param sneak           按住潜行再点,见 {@link #crouched}
     */
    public static Interaction forHit(NumenPlayer p, HitResult hit, Button button, int holdTicks,
                                     boolean itemFallthrough, boolean sneak) {
        Interaction i = press(p, hit, button, holdTicks, itemFallthrough);
        if (i != null) {
            i.sneak = sneak;
        }
        return i;
    }

    private static Interaction press(NumenPlayer p, HitResult hit, Button button, int holdTicks,
                                     boolean itemFallthrough) {
        boolean hold = holdTicks != 0;
        switch (hit.getType()) {
            case BLOCK -> {
                BlockHitResult bh = (BlockHitResult) hit;
                if (button == Button.ATTACK) {
                    return attackBlock(p, bh, hold);
                }
                Interaction i = new Interaction(p, Button.USE, bh.getBlockPos(), null,
                        InteractionHand.MAIN_HAND,
                        hold ? Timing.repeat(CONTINUOUS, RIGHT_CLICK_DELAY) : Timing.once());
                i.presetHit = bh;   // use the robust native hit, no re-raycast
                i.itemFallthrough = itemFallthrough;
                return i;
            }
            case ENTITY -> {
                Entity e = ((EntityHitResult) hit).getEntity();
                if (button == Button.ATTACK) {
                    return attackEntity(p, e, hold ? Timing.repeat(CONTINUOUS, 1) : Timing.once());
                }
                Interaction i = new Interaction(p, Button.USE, null, e, InteractionHand.MAIN_HAND,
                        hold ? Timing.repeat(CONTINUOUS, RIGHT_CLICK_DELAY) : Timing.once());
                i.itemFallthrough = itemFallthrough;
                return i;
            }
            default -> {   // MISS = air
                if (button == Button.ATTACK) {
                    return null;
                }
                Timing t = hold ? (holdTicks > 0 ? Timing.hold(holdTicks) : Timing.hold()) : Timing.once();
                return useInAir(p, InteractionHand.MAIN_HAND, t);
            }
        }
    }

    public String failReason() {
        return failReason;
    }

    /** Structured cause of a {@link Status#FAILED}, for the reactive task layer to branch on. */
    public FailureType failType() {
        return failType;
    }

    public Status tick() {
        if (!crouched()) {
            return Status.RUNNING;
        }
        if (button == Button.ATTACK && block != null) {
            return breakBlock();                       // inherently continuous
        }
        if (button == Button.USE && block == null && entity == null) {
            return useAir();
        }
        return discrete();                             // attack entity / use block / use entity
    }

    /**
     * 按住潜行再点:这一下点下去时她是不是已经蹲好了。没要潜行就总是蹲好了。
     *
     * <p>原版服务端判"按着潜行"读的是 {@code isShiftKeyDown}(方块与物品让不让潜行右键越过方块自己的反应,走的是
     * {@code isSecondaryUseActive},就是它);身体的姿态({@code isCrouching})要等下一次身体 tick 才跟上,有的模组看的是
     * 姿态。所以先按下潜行键,等服务端看到她按着({@link com.dwinovo.numen.pathing.body.Controls} 在身体的物理步进里把键落到
     * {@code setShiftKeyDown}),再多等一刻让姿态跟上,才点——和真玩家先按住 Shift 再点一样。按键每刻都按一下:被抢占时
     * 身体的键全松了,回来接着点之前重新蹲好。{@code Controls.stop()} 只松移动键,潜行一直按到 {@link #stop}。
     */
    private boolean crouched() {
        if (!sneak) {
            return true;
        }
        player.controls().press(com.dwinovo.numen.pathing.body.Controls.Key.SNEAK);
        if (!player.isShiftKeyDown()) {
            crouchSettled = false;
            return false;
        }
        if (!crouchSettled) {
            crouchSettled = true;
            return false;
        }
        return true;
    }

    // ---- ATTACK + block: press, or hold the button on it ----

    /**
     * 左键一格:每刻朝按下时的那一点看着,准星还落在那一格上就按;准星被挡开了(有东西走进来)就等着,不去按挡着的。点一下
     * ({@link Timing#once})等手缓过来、真按下去一下就松手,按住的那一格碎了才松手。权限层在第一下之前把门(同一格接着按不再问),被拒只转述、
     * 不换法子。
     */
    private Status breakBlock() {
        if (player.level().getBlockState(block).isAir()) return Status.DONE;
        player.controls().stop();
        InputDriver.lookAt(player, presetHit.getLocation());
        BlockHitResult hit = Crosshair.on(player, block);
        if (hit == null) {
            return Status.RUNNING;
        }
        return switch (CompanionHands.of(player).dig(hit)) {
            case Effector.Strike.Swinging swinging -> timing.hold || !swinging.pressed() ? Status.RUNNING
                    : Status.DONE;
            case Effector.Strike.Broke broke -> Status.DONE;
            case Effector.Strike.Refused refused -> {
                Verdict verdict = CompanionHands.verdict(refused.reason());
                failReason = "cannot break that block: " + (verdict != null ? verdict.reason()
                        : BlockDigger.SERVER_REFUSED);
                failType = FailureType.REFUSED;
                hardFail = true;
                yield Status.FAILED;
            }
        };
    }

    // ---- USE + air: tap or hold (food / bow) ----

    private Status useAir() {
        player.controls().stop();
        if (!started) {
            started = true;
            player.gameMode.useItem(player, player.level(), player.getItemInHand(hand), hand);
            if (!timing.hold) return Status.DONE;      // single tap (throw / instant use)
        }
        if (!player.isUsingItem()) return Status.DONE; // e.g. food finished eating
        if (timing.maxHold > 0 && ++held >= timing.maxHold) {
            player.releaseUsingItem();                 // e.g. loose the bow
            return Status.DONE;
        }
        return Status.RUNNING;
    }

    // ---- discrete: attack entity / use block / use entity (once or repeat) ----

    private Status discrete() {
        if (cooldown > 0) {
            cooldown--;
            return Status.RUNNING;
        }
        boolean fired = switch (button) {
            case ATTACK -> fireAttackEntity();
            case USE -> entity != null ? fireUseEntity() : fireUseBlock();
        };
        if (hardFail) return Status.FAILED;
        if (!fired) return Status.RUNNING;             // soft wait (attack cooldown not ready)
        if (++fires >= timing.limit) return Status.DONE;
        cooldown = timing.interval;
        return Status.RUNNING;
    }

    private boolean fireAttackEntity() {
        if (entity == null || !entity.isAlive()) return false;
        // 攻击落点:宠物、有名字的、村民,主人没点头就不出手
        com.dwinovo.numen.permission.Verdict verdict = com.dwinovo.numen.permission.Permission.judge(
                player, com.dwinovo.numen.permission.Action.attack(entity));
        if (!verdict.allowed()) {
            failReason = "cannot attack " + entity.getName().getString() + ": " + verdict.reason();
            failType = FailureType.REFUSED;
            hardFail = true;
            return false;
        }
        player.controls().stop();
        InputDriver.lookAt(player, entity.getEyePosition());
        boolean recovering = entity instanceof net.minecraft.world.entity.LivingEntity living
                && living.hurtTime > 0;
        // 无敌帧与冷却的判据在 Swing 里,战斗任务用的是同一处。
        if (!com.dwinovo.numen.core.combat.Swing.mayStrike(
                false, recovering, player.getAttackStrengthScale(0.0f))) {
            return false;
        }
        player.setSprinting(false);                    // 疾跑会让原版取消暴击判定
        player.attack(entity);                         // native damage / cooldown / sweep / knockback (resets the ticker itself)
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private boolean fireUseBlock() {
        player.controls().stop();
        net.minecraft.world.inventory.AbstractContainerMenu menuBefore = player.containerMenu;
        BlockHitResult hit = presetHit;
        InputDriver.lookAt(player, hit.getLocation());
        StringBuilder outcome = new StringBuilder();
        for (InteractionHand h : HANDS) {
            InteractionResult res = player.gameMode.useItemOn(
                    player, player.level(), player.getItemInHand(h), h, hit);
            String handName = h == InteractionHand.MAIN_HAND ? "main_hand" : "off_hand";
            if (res.consumesAction()) {
                player.swing(h);
                lastUseOutcome = "consumed (" + handName + "=" + res + ")";
                MenuOrigin.pressed(player, menuBefore, hit.getBlockPos());
                return true;
            }
            if (outcome.length() > 0) outcome.append(", ");
            outcome.append(handName).append('=').append(res);
        }
        if (fallthroughUse()) {
            return true;
        }
        // Nothing consumed (e.g. empty hand on a non-interactive block) — still a press.
        lastUseOutcome = outcome.toString();
        return true;
    }

    /**
     * 准星 USE 的收尾一步:方块/实体都没吃掉点击时,把同一次按键落到物品自用——
     * 与真客户端一致(useItemOn 不消费就发 ServerboundUseItemPacket → Item.use)。
     * 桶、船、钓竿这类物品的行为全写在 Item.use 里,自带各自的流体射线,
     * 所以准星根本不需要点中水。开关见 {@link #itemFallthrough}。
     *
     * @return true = 有一只手的物品吃掉了这次按键
     */
    private boolean fallthroughUse() {
        if (!itemFallthrough) {
            return false;
        }
        for (InteractionHand h : HANDS) {
            if (player.gameMode.useItem(player, player.level(),
                    player.getItemInHand(h), h).consumesAction()) {
                player.swing(h);
                lastUseOutcome = "consumed (item self-use, "
                        + (h == InteractionHand.MAIN_HAND ? "main_hand" : "off_hand") + ")";
                return true;
            }
        }
        return false;
    }

    /**
     * The vanilla verdict of the most recent USE-on-block press: {@code "consumed (...)"}
     * or the per-hand results (e.g. {@code "main_hand=FAIL, off_hand=PASS"}). A press that
     * consumes can STILL have placed nothing (the item's own rules refused) — placement
     * callers must verify the world afterwards, and this string is what they log when a
     * press quietly did nothing.
     */
    public String lastUseOutcome() {
        return lastUseOutcome;
    }

    private boolean fireUseEntity() {
        if (entity == null || !entity.isAlive()) {
            failReason = "the entity is gone";
            failType = FailureType.TARGET_LOST;
            hardFail = true;
            return false;
        }
        player.controls().stop();
        InputDriver.lookAt(player, entity.getEyePosition());
        net.minecraft.world.inventory.AbstractContainerMenu menuBefore = player.containerMenu;
        for (InteractionHand h : HANDS) {
            if (entity.interact(player, h).consumesAction()) {       // animals / villagers
                MenuOrigin.pressed(player, menuBefore, null);
                return true;
            }
            if (player.interactOn(entity, h).consumesAction()) {     // item frames / leads
                MenuOrigin.pressed(player, menuBefore, null);
                return true;
            }
        }
        fallthroughUse();          // 实体没吃掉点击:真客户端同样落到物品自用
        return true;               // a press with no effect is still a press
    }

    /** Abandon any in-progress interaction (clears a dig overlay / releases a held use / lets go of sneak). */
    public void stop() {
        if (button == Button.ATTACK && block != null) CompanionHands.of(player).release();
        if (player.isUsingItem()) player.releaseUsingItem();
        player.controls().stop();
        if (sneak) player.controls().release(com.dwinovo.numen.pathing.body.Controls.Key.SNEAK);
    }
}
