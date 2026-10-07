package com.dwinovo.numen.act;

import com.dwinovo.numen.api.entity.Mouse;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.FailureType;
import com.dwinovo.numen.api.permission.Verdict;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The most-native interaction primitive for a fake-player body: look at a target, then press
 * one mouse button (left = ATTACK, right = USE) with a {@link Timing}. Every
 * higher-level action is a thin layer on top: mining = ATTACK a block (hold),
 * {@code attack} = ATTACK an entity, eat/bow = hold USE in the air.
 *
 * <p>按键本身是她的鼠标({@link NumenPlayer#mouse})的事,这里只管"什么时候按、按几次、按多久"和被拒、目标没了怎么说:
 * <ul>
 *   <li>ATTACK + block  → {@link Mouse#dig} on the block the crosshair lands on (the vanilla dig loop behind the permission
 *       layer; creative insta / survival timed), with whatever is held</li>
 *   <li>ATTACK + entity → {@code player.attack} (cooldown-scaled damage / sweep / knockback)</li>
 *   <li>USE + block / entity → {@link Mouse#use}: the one right click, on whatever the crosshair lands on</li>
 *   <li>USE + air       → {@link Mouse#useItem} (+ a hold for food / bow)</li>
 * </ul>
 *
 * <p>右键按下去总是对准星落着的东西:调用方给的命中({@link #forHit})只是转过去看哪儿,这里不另打射线、不替准星选目标。
 * 准星语义的 USE 另有一步收尾:方块/实体没吃掉点击时落到物品自用(真客户端的完整右键顺序),身体约束物品时由任务层把它关掉。
 *
 * <h2>Timing</h2>
 * {@link Timing#once()} taps once; {@link Timing#repeat} taps N times spaced by an
 * interval (grind a mob); {@link Timing#hold()} holds the
 * button until the action self-completes (a block breaks, food finishes);
 * {@link Timing#hold(int)} holds up to N ticks then releases (draw + loose a bow).
 * 右键按住是每刻都按,两次按下的间隔(原版的 4 刻)由鼠标管。
 *
 * <p>Stateful + ticked (like {@link BlockDigger} / {@code PlayerNav}). The caller
 * walks the body within reach first; this only looks and presses.
 */
public final class Interaction {

    public enum Status { RUNNING, DONE, FAILED }
    public enum Button { ATTACK, USE }

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
    private final Timing timing;

    /** 按之前朝哪一点看:方块目标是命中的那一点,实体目标是它的眼睛;朝空气按没有。 */
    private final Vec3 lookAt;
    /**
     * 准星语义的 USE 才有的兜底:方块/实体没吃掉点击时,同一次按键落到物品自用({@code gameMode.useItem})——桶找水、船找
     * 水面、掷物出手都住在那条路上。false = 兜底关闭或被任务层否决(身体约束物品)。
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
    private boolean opened;           // a press opened a window (a chest, a trade, a machine)
    private String failReason = "interaction failed";
    private FailureType failType = FailureType.UNKNOWN;

    private Interaction(NumenPlayer player, Button button, BlockPos block, Entity entity, Vec3 lookAt, Timing timing) {
        this.player = player;
        this.button = button;
        this.block = block == null ? null : block.immutable();
        this.entity = entity;
        this.lookAt = lookAt;
        this.timing = timing;
    }

    // ---- factories (default timings; overloads take an explicit Timing) ----

    /**
     * 左键按在准星落着的那一格上({@code hit}),按住直到它碎:手上是什么就用什么,不换工具、不挪步、不清别的格——一次纯按键。
     * 创造一下就碎,生存按手上的东西算时间,都是原版的手自己分。
     */
    public static Interaction attackBlock(NumenPlayer p, BlockHitResult hit, boolean hold) {
        return new Interaction(p, Button.ATTACK, hit.getBlockPos(), null, hit.getLocation(),
                hold ? Timing.hold() : Timing.once());
    }

    /** Left-click an entity once (cooldown-gated native attack). */
    public static Interaction attackEntity(NumenPlayer p, Entity target) {
        return attackEntity(p, target, Timing.once());
    }

    public static Interaction attackEntity(NumenPlayer p, Entity target, Timing timing) {
        return new Interaction(p, Button.ATTACK, null, target, target.getEyePosition(), timing);
    }

    /** 右键准星落着的那一格:转过去看 {@code hit} 那一点再按一下。 */
    public static Interaction useBlock(NumenPlayer p, BlockHitResult hit) {
        return new Interaction(p, Button.USE, hit.getBlockPos(), null, hit.getLocation(), Timing.once());
    }

    /** Right-click in the air with the held item, on the given {@link Timing}
     *  ({@code hold()} eats food / {@code hold(n)} draws and looses a bow). */
    public static Interaction useInAir(NumenPlayer p, Timing timing) {
        return new Interaction(p, Button.USE, null, null, null, timing);
    }

    /** A "hold forever" fire count; the owning task stops us after hold_ticks / on completion. */
    private static final int CONTINUOUS = 1_000_000;

    /**
     * Build the native action for a resolved crosshair {@code hit} + {@code button}, mapping
     * {@code holdTicks} to the cell's natural cadence — a 6-cell (button × target) dispatch:
     * <ul>
     *   <li>ATTACK·BLOCK → hit it (tap = one press, which breaks only what breaks at once; hold = till the block is
     *       gone);</li>
     *   <li>ATTACK·ENTITY → hit (tap = one cooldown-gated hit; hold = keep hitting);</li>
     *   <li>USE·BLOCK → activate (tap once; hold re-clicks as the mouse allows — modded crank);</li>
     *   <li>USE·ENTITY → interact (tap once; hold re-clicks);</li>
     *   <li>USE·AIR → useItem (tap = throw; hold = charge/eat up to ticks, or self-complete);</li>
     *   <li>ATTACK·AIR → {@code null} (left-click air does nothing).</li>
     * </ul>
     * {@code holdTicks}: 0 = tap, &gt;0 / -1 = hold. {@code hit} says where to look; what gets pressed is whatever the
     * crosshair lands on once she looks there. The caller drives the returned object to completion and enforces the
     * hold duration.
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
                Interaction i = new Interaction(p, Button.USE, bh.getBlockPos(), null, bh.getLocation(),
                        hold ? Timing.repeat(CONTINUOUS, 1) : Timing.once());
                i.itemFallthrough = itemFallthrough;
                return i;
            }
            case ENTITY -> {
                Entity e = ((EntityHitResult) hit).getEntity();
                if (button == Button.ATTACK) {
                    return attackEntity(p, e, hold ? Timing.repeat(CONTINUOUS, 1) : Timing.once());
                }
                Interaction i = new Interaction(p, Button.USE, null, e, e.getEyePosition(),
                        hold ? Timing.repeat(CONTINUOUS, 1) : Timing.once());
                i.itemFallthrough = itemFallthrough;
                return i;
            }
            default -> {   // MISS = air
                if (button == Button.ATTACK) {
                    return null;
                }
                Timing t = hold ? (holdTicks > 0 ? Timing.hold(holdTicks) : Timing.hold()) : Timing.once();
                return useInAir(p, t);
            }
        }
    }

    public String failReason() {
        return failReason;
    }

    /** 按下去的哪一下打开了一个界面(箱子、交易、模组机器)。 */
    public boolean opened() {
        return opened;
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
     * 姿态。所以先按下潜行键,等服务端看到她按着({@link com.dwinovo.numen.api.entity.Controls} 在身体的物理步进里把键落到
     * {@code setShiftKeyDown}),再多等一刻让姿态跟上,才点——和真玩家先按住 Shift 再点一样。按键每刻都按一下:被抢占时
     * 身体的键全松了,回来接着点之前重新蹲好。{@code Controls.stop()} 只松移动键,潜行一直按到 {@link #stop}。
     */
    private boolean crouched() {
        if (!sneak) {
            return true;
        }
        player.controls().press(com.dwinovo.numen.api.entity.Controls.Key.SNEAK);
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
     * ({@link Timing#once})等手缓过来、真按下去一下就松手,按住的那一格碎了才松手。权限层在第一下之前把门(同一格接着按不再问,
     * 门在鼠标里),被拒只转述、不换法子。
     */
    private Status breakBlock() {
        if (player.level().getBlockState(block).isAir()) return Status.DONE;
        player.controls().stop();
        player.look().at(lookAt);
        if (player.mouse().on(block) == null) {
            return Status.RUNNING;
        }
        return switch (player.mouse().dig()) {
            case Mouse.Strike.Swinging swinging -> timing.hold || !swinging.pressed() ? Status.RUNNING
                    : Status.DONE;
            case Mouse.Strike.Broke broke -> Status.DONE;
            case Mouse.Strike.Missed missed -> Status.RUNNING;
            case Mouse.Strike.Refused refused -> {
                Verdict verdict = refused.reason().verdict();
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
            if (player.mouse().useItem() instanceof Mouse.Use.Waiting) {
                return Status.RUNNING;                 // 上一下右键的缓手还没过,下一刻再按
            }
            started = true;
            if (!timing.hold) return Status.DONE;      // single tap (throw / instant use)
        }
        if (!player.isUsingItem()) return Status.DONE; // e.g. food finished eating
        if (timing.maxHold > 0 && ++held >= timing.maxHold) {
            player.mouse().stopUse();                  // e.g. loose the bow
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
            case USE -> fireUse();
        };
        if (hardFail) return Status.FAILED;
        if (!fired) return Status.RUNNING;             // soft wait (attack cooldown / right-click delay not ready)
        if (++fires >= timing.limit) return Status.DONE;
        cooldown = timing.interval;
        return Status.RUNNING;
    }

    private boolean fireAttackEntity() {
        if (entity == null || !entity.isAlive()) return false;
        player.controls().stop();
        player.look().at(entity.getEyePosition());
        boolean recovering = entity instanceof net.minecraft.world.entity.LivingEntity living
                && living.hurtTime > 0;
        // 无敌帧与冷却的判据在 Swing 里,战斗任务用的是同一处。
        if (!com.dwinovo.numen.combat.Swing.mayStrike(
                false, recovering, player.getAttackStrengthScale(0.0f))) {
            return false;
        }
        // 打准星落着的那只:原版的伤害、冷却、横扫、击退;宠物、有名字的、村民,主人没点头鼠标就不出手
        return switch (player.mouse().attack()) {
            case Mouse.Blow.Landed landed -> true;
            case Mouse.Blow.Missed missed -> false;       // 准星还没落在它身上,下一刻再来
            case Mouse.Blow.Refused refused -> {
                Verdict verdict = refused.reason().verdict();
                failReason = "cannot attack " + refused.target().getName().getString() + ": " + verdict.reason();
                failType = FailureType.REFUSED;
                hardFail = true;
                yield false;
            }
        };
    }

    /** 右键方块或实体:转过去看着,按一下准星落着的东西。 */
    private boolean fireUse() {
        if (entity != null && !entity.isAlive()) {
            failReason = "the entity is gone";
            failType = FailureType.TARGET_LOST;
            hardFail = true;
            return false;
        }
        player.controls().stop();
        player.look().at(lookAt);
        return switch (player.mouse().use(itemFallthrough)) {
            case Mouse.Use.Waiting waiting -> false;
            case Mouse.Use.Refused refused -> {
                Verdict verdict = refused.reason().verdict();
                failReason = "cannot use that: " + (verdict != null ? verdict.reason() : "the server would not let it happen");
                failType = FailureType.REFUSED;
                hardFail = true;
                yield false;
            }
            case Mouse.Use.Pressed pressed -> {
                if (pressed.opened()) {
                    opened = true;
                    MenuOrigin.opened(player, pressed.hit() instanceof BlockHitResult hit
                            && hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null);
                }
                yield true;                            // a press with no effect is still a press
            }
        };
    }

    /** Abandon any in-progress interaction (clears a dig overlay / releases a held use / lets go of sneak). */
    public void stop() {
        if (button == Button.ATTACK && block != null) player.mouse().release();
        player.mouse().stopUse();
        player.controls().stop();
        if (sneak) player.controls().release(com.dwinovo.numen.api.entity.Controls.Key.SNEAK);
    }
}
