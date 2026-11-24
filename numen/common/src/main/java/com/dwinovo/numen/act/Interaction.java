package com.dwinovo.numen.act;

import com.dwinovo.numen.api.entity.Mouse;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.FailureType;
import com.dwinovo.numen.api.permission.Verdict;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The most-native interaction primitive for a fake-player body: turn toward a named target, then press
 * one mouse button (left = ATTACK, right = USE) on it with a {@link Timing}. Every
 * higher-level action is a thin layer on top: mining = ATTACK a block (hold),
 * {@code attack} = ATTACK an entity, eat/bow = hold USE in the air.
 *
 * <p>按键本身是她的鼠标({@link NumenPlayer#mouse})的事,这里只管"什么时候按、按几次、按多久"和被拒、目标没了怎么说:
 * <ul>
 *   <li>ATTACK + block  → {@link Mouse#dig} on the named block (the vanilla dig loop behind the permission
 *       layer; creative insta / survival timed), with whatever is held</li>
 *   <li>ATTACK + entity → {@code player.attack} (cooldown-scaled damage / sweep / knockback)</li>
 *   <li>USE + block / entity → {@link Mouse#use}: the one right click, on the named block face or entity</li>
 *   <li>USE + air       → {@link Mouse#useItem} (+ a hold for food / bow)</li>
 * </ul>
 *
 * <p>按在哪一格、哪一面、哪只实体上由调用方指名({@link #forHit} 收的那一下):够得着就按得中,转头只是给看着她的人看的。
 * USE 另有一步收尾:方块/实体没吃掉点击时落到物品自用(真客户端的完整右键顺序),身体约束物品时由任务层把它关掉。
 *
 * <h2>Timing</h2>
 * {@link Timing#once()} taps once; {@link Timing#repeat} taps N times spaced by an
 * interval (grind a mob); {@link Timing#hold()} holds the
 * button until the action self-completes (a block breaks, food finishes);
 * {@link Timing#hold(int)} holds up to N ticks then releases (draw + loose a bow).
 * 右键按住是每刻都按一下。
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
    private final Direction face;     // USE + block: the face clicked
    private final Entity entity;      // non-null → entity target
    private final Timing timing;

    /** 按之前朝哪一点转:方块目标是点在它身上的那一点;实体会动,按的那一刻朝它身上最近的一点转,这里没有;朝空气按也没有。 */
    private final Vec3 lookAt;
    /**
     * 点一格、一只实体的 USE 才有的兜底:方块/实体没吃掉点击时,同一次按键落到物品自用({@code gameMode.useItem})——桶找水、船找
     * 水面、掷物出手都住在那条路上。false = 兜底关闭或被任务层否决(身体约束物品)。
     */
    private boolean itemFallthrough;
    /** 按住潜行再点({@code --sneak}),见 {@link #crouched}。 */
    private boolean sneak;
    /** 右键实体时点在它身上的哪一点(相对实体位置),见 {@link #pointing};没指定为 null。 */
    private Vec3 at;
    /** 上一刻服务端就已经看到她按着潜行({@code isShiftKeyDown}),这一刻姿态也跟上了。 */
    private boolean crouchSettled;
    private int fires;
    private int cooldown;             // ticks until the next discrete press
    private boolean started;          // USE+air: the hold has begun
    private int held;                 // USE+air: ticks held so far
    private boolean hardFail;         // a fire hit an unrecoverable error
    private boolean opened;           // a press opened a window (a chest, a trade, a machine)
    /** 右键按下去之后游戏交回的话;按了几下取其中最好的一个,一下都没按到为 null。 */
    private Mouse.Use.Answer answer;
    /** 左键按着的那一格碎了。 */
    private boolean broke;
    private String failReason = "interaction failed";
    private FailureType failType = FailureType.UNKNOWN;

    private Interaction(NumenPlayer player, Button button, BlockHitResult block, Entity entity, Vec3 lookAt, Timing timing) {
        this.player = player;
        this.button = button;
        this.block = block == null ? null : block.getBlockPos().immutable();
        this.face = block == null ? null : block.getDirection();
        this.entity = entity;
        this.lookAt = lookAt;
        this.timing = timing;
    }

    // ---- factories (default timings; overloads take an explicit Timing) ----

    /**
     * 左键按在 {@code hit} 指名的那一格上,按住直到它碎:手上是什么就用什么,不换工具、不挪步——一次纯按键。
     * 创造一下就碎,生存按手上的东西算时间,都是原版的手自己分。
     */
    public static Interaction attackBlock(NumenPlayer p, BlockHitResult hit, boolean hold) {
        return new Interaction(p, Button.ATTACK, hit, null, hit.getLocation(), hold ? Timing.hold() : Timing.once());
    }

    /** Left-click an entity once (cooldown-gated native attack). */
    public static Interaction attackEntity(NumenPlayer p, Entity target) {
        return attackEntity(p, target, Timing.once());
    }

    public static Interaction attackEntity(NumenPlayer p, Entity target, Timing timing) {
        return new Interaction(p, Button.ATTACK, null, target, target.getEyePosition(), timing);
    }

    /** 右键 {@code hit} 指名的那一格的那一面:朝那一点转过去再按一下。 */
    public static Interaction useBlock(NumenPlayer p, BlockHitResult hit) {
        return new Interaction(p, Button.USE, hit, null, hit.getLocation(), Timing.once());
    }

    /** Right-click in the air with the held item, on the given {@link Timing}
     *  ({@code hold()} eats food / {@code hold(n)} draws and looses a bow). */
    public static Interaction useInAir(NumenPlayer p, Timing timing) {
        return new Interaction(p, Button.USE, null, null, null, timing);
    }

    /** A "hold forever" fire count; the owning task stops us after hold_ticks / on completion. */
    private static final int CONTINUOUS = 1_000_000;

    /**
     * Build the native action for a named target ({@code hit}: a block and its face from {@link Mouse#aim}, an entity, or
     * a miss for "nothing in particular") + {@code button}, mapping
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
     * {@code holdTicks}: 0 = tap, &gt;0 / -1 = hold. What gets pressed is what {@code hit} names. The caller drives the
     * returned object to completion and enforces the hold duration.
     *
     * @param itemFallthrough USE 的兜底开关(见 {@link #itemFallthrough}):方块/实体
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
                Interaction i = new Interaction(p, Button.USE, bh, null, bh.getLocation(),
                        hold ? Timing.repeat(CONTINUOUS, 1) : Timing.once());
                i.itemFallthrough = itemFallthrough;
                return i;
            }
            case ENTITY -> {
                Entity e = ((EntityHitResult) hit).getEntity();
                if (button == Button.ATTACK) {
                    return attackEntity(p, e, hold ? Timing.repeat(CONTINUOUS, 1) : Timing.once());
                }
                Interaction i = new Interaction(p, Button.USE, null, e, null,
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

    /**
     * 右键实体时点在它身上的这一点(相对实体位置):按下去先发 {@code INTERACT_AT},和真客户端点盔甲架的哪一截一样,见
     * {@link Mouse#use(Entity, boolean, Vec3)}。只对右键实体起作用。
     */
    public Interaction pointing(Vec3 at) {
        this.at = at;
        return this;
    }

    public String failReason() {
        return failReason;
    }

    /** 按下去的哪一下打开了一个界面(箱子、交易、模组机器)。 */
    public boolean opened() {
        return opened;
    }

    /** 右键按下去之后游戏交回的话;没按到(左键,或还没轮到按)为 null。 */
    public Mouse.Use.Answer answer() {
        return answer;
    }

    public Button button() {
        return button;
    }

    /** 按的是一只实体(不是一格方块,也不是朝空气)。 */
    public boolean onEntity() {
        return entity != null;
    }

    /**
     * 这一下游戏接没接:右键是方块、实体或手里的东西有一样接了;左键一格是它碎了;左键实体是至少落下去一下。
     * 只说接没接,不说之后世界变成了什么样。
     */
    public boolean accepted() {
        if (button == Button.USE) {
            return answer == Mouse.Use.Answer.TAKEN;
        }
        return entity != null ? fires > 0 : broke;
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
     * 左键一格:每刻朝它转着、按在它上面。点一下({@link Timing#once})按下去一下就松手,按住的那一格碎了才松手;够不着了
     * (身体被挪开)就等着。权限层在第一下之前把门(同一格接着按不再问,门在鼠标里),被拒只转述、不换法子。
     */
    private Status breakBlock() {
        if (player.level().getBlockState(block).isAir()) return Status.DONE;
        player.controls().stop();
        player.look().at(lookAt);
        return switch (player.mouse().dig(block)) {
            case Mouse.Strike.Swinging swinging -> timing.hold ? Status.RUNNING : Status.DONE;
            case Mouse.Strike.Broke done -> {
                broke = true;
                yield Status.DONE;
            }
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
            if (!(player.mouse().useItem() instanceof Mouse.Use.Pressed pressed)) {
                return Status.RUNNING;                 // 手上正挖着,下一刻再按
            }
            landed(pressed);
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
        if (!fired) return Status.RUNNING;             // soft wait (attack cooldown not ready / out of reach)
        if (++fires >= timing.limit) return Status.DONE;
        cooldown = timing.interval;
        return Status.RUNNING;
    }

    private boolean fireAttackEntity() {
        if (entity == null || !entity.isAlive()) return false;
        player.controls().stop();
        player.look().atNearest(entity.getBoundingBox());
        boolean recovering = entity instanceof net.minecraft.world.entity.LivingEntity living
                && living.hurtTime > 0;
        // 无敌帧与冷却的判据在 Swing 里,战斗任务用的是同一处。
        if (!com.dwinovo.numen.combat.Swing.mayStrike(
                false, recovering, player.getAttackStrengthScale(0.0f))) {
            return false;
        }
        // 打指名的这一只:原版的伤害、冷却、横扫、击退;宠物、有名字的、村民,主人没点头鼠标就不出手
        return switch (player.mouse().attack(entity)) {
            case Mouse.Blow.Landed landed -> true;
            case Mouse.Blow.Missed missed -> false;       // 它够不着了,下一刻再来
            case Mouse.Blow.Refused refused -> {
                Verdict verdict = refused.reason().verdict();
                failReason = "cannot attack " + refused.target().getName().getString() + ": " + verdict.reason();
                failType = FailureType.REFUSED;
                hardFail = true;
                yield false;
            }
        };
    }

    /** 右键方块或实体:朝它转过去,按在指名的那一格那一面、那一只上(够得着就按得中)。 */
    private boolean fireUse() {
        if (entity != null && !entity.isAlive()) {
            failReason = "the entity is gone";
            failType = FailureType.TARGET_LOST;
            hardFail = true;
            return false;
        }
        player.controls().stop();
        if (entity != null) {
            player.look().atNearest(entity.getBoundingBox());
        } else {
            player.look().at(lookAt);
        }
        return switch (entity != null ? player.mouse().use(entity, itemFallthrough, at)
                : player.mouse().use(block, face, itemFallthrough)) {
            case Mouse.Use.Waiting waiting -> false;
            case Mouse.Use.Refused refused -> {
                Verdict verdict = refused.reason().verdict();
                failReason = "cannot use that: " + (verdict != null ? verdict.reason() : "the server would not let it happen");
                failType = FailureType.REFUSED;
                hardFail = true;
                yield false;
            }
            case Mouse.Use.Pressed pressed -> {
                landed(pressed);
                yield true;                            // a press with no effect is still a press
            }
        };
    }

    /**
     * 右键按下去了:记下游戏交回的话;这一下打开了界面也记下来。对着方块、实体、空气按的
     * 三条路都从这里过——手里的东西对着空气用也能打开界面(模组的背包、无线终端)。
     */
    private void landed(Mouse.Use.Pressed pressed) {
        if (answer == null || pressed.answer().compareTo(answer) < 0) {
            answer = pressed.answer();     // 枚举的先后就是好坏:接了、做不成、没人理
        }
        if (pressed.opened()) {
            opened = true;
        }
    }

    /** Abandon any in-progress interaction (clears a dig overlay / releases a held use / lets go of sneak). */
    public void stop() {
        if (button == Button.ATTACK && block != null) player.mouse().release();
        player.mouse().stopUse();
        player.controls().stop();
        if (sneak) player.controls().release(com.dwinovo.numen.api.entity.Controls.Key.SNEAK);
    }
}
