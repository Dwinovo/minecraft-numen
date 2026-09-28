package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.nav.Terrain;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.body.Controls;
import com.dwinovo.numen.pathing.body.Hotbar;
import com.dwinovo.numen.pathing.body.Aim;
import com.dwinovo.numen.pathing.world.Semantics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 施工的演出:绕着工地外圈走、转头、蹲起、挥手、手到落点的粒子、落位声。只管"看起来像有人在干活",不碰任何施工判定与
 * 账目——删掉整个类,房子照样盖得出来,只是看着像作弊。
 *
 * <h2>表演不改变世界</h2>
 * 它手里只有身体的输入(移动、转头、挥手、蹲)和一张只读的世界视图:没有挖掘器,没有导航,没有放置的入口。绕圈是一格一格
 * 地踩着地面走到外圈上相邻的下一列,不垫块、不挖、不开门,也不走会改地形的寻路。走不过去的那一段(树、水、坑、别的房子)
 * 就掉头往回绕;两头都走不通,就站在原地看着正在长的方块。表演走不通不算失败,也不影响施工——施工那一侧从不问它。
 *
 * <p>小活({@link BuildOrder#instant})没有外圈可绕,构造时不给圈,它就只做手上的那几样。
 */
final class BuildShowmanship {

    /** 挥臂间隔:与原版挥臂动画一轮的长度对齐。 */
    private static final int SWING_PERIOD_TICKS = 6;
    /** 手到落点那道粒子的采样点数。 */
    private static final int CONJURE_TRAIL_SAMPLES = 6;
    /** 每批最多冒几处粒子——整栋房子逐格发粒子会把客户端打垮。 */
    private static final int PARTICLE_BUDGET_PER_BATCH = 3;

    /** 离最近一列的中心这么近,才算站在圈上、可以接着绕。 */
    private static final double ON_RING = 1.0;
    /** 离要去的那一列中心这么近就算到了,挑下一列。 */
    private static final double ARRIVED = 0.3;
    /** 往那一列走了这么多刻还没近一点,就当这一段走不通,掉头。 */
    private static final int STUCK_TICKS = 30;
    /** 转头看落点之后,这么多刻里脸一直朝着那儿;再没放东西,脸转回走的方向。 */
    private static final int LOOK_HOLD_TICKS = 20;

    /**
     * 绕圈不碰的格子种类:要游要爬的(水、流水、攀爬)、伤身的(岩浆、危险方块)、踩坏的(耕地、海龟蛋)、一碰就触发的
     * (压力板、绊线),还有门——表演不开门。落脚那一格、托着脚的那一格、身体经过的格都不能是。
     */
    private static final Set<Semantics.Kind> KEEP_OFF = EnumSet.of(Semantics.Kind.WATER,
            Semantics.Kind.FLOWING_WATER, Semantics.Kind.CLIMBABLE, Semantics.Kind.LAVA, Semantics.Kind.HAZARD,
            Semantics.Kind.FRAGILE, Semantics.Kind.TRIGGER, Semantics.Kind.DOOR);

    private final NumenPlayer player;
    private final BuildInventory inv;
    /** 绕的那一圈;小活没有,是 null。 */
    private final SiteRing ring;

    private int swingCooldown;
    /** 落位批次的高度要她蹲下(跨 tick 保持)。 */
    private boolean crouching;
    /** 上一次转头看落点之后过了几刻。 */
    private int sinceAim = LOOK_HOLD_TICKS;

    /** 正往圈上哪一列走;还没上圈是 -1。 */
    private int heading = -1;
    /** 那一列站脚的高度。 */
    private int headingY;
    /** 走进那一列要起跳。 */
    private boolean headingJump;
    /** 刚离开的那一列站脚的高度:这一段走不过去掉头时,回去就踩在那儿。 */
    private int leftY;
    /** 顺着圈往哪边走:+1 顺时针,-1 逆时针。 */
    private int dir = 1;
    /** 离那一列最近到过多近,和多少刻没再近过。 */
    private double closest;
    private int stalled;

    BuildShowmanship(NumenPlayer player, BuildInventory inv, SiteRing ring) {
        this.player = player;
        this.inv = inv;
        this.ring = ring;
    }

    /** 她站在外圈上吗(小活没有圈,永远不在)。 */
    boolean onRing() {
        return ring != null && ring.distance(ring.nearest(player.getX(), player.getZ()), player.getX(),
                player.getZ()) <= ON_RING;
    }

    /**
     * 施工的每一刻调一次,在落位之后(脸朝哪儿已经定了):沿外圈往下一列走;走不了就站着。
     */
    void walk() {
        sinceAim++;
        if (ring == null) {
            stand();
            return;
        }
        if (heading < 0) {
            if (!onRing()) {
                stand();   // 不在圈上:演出不负责把她弄回来,站着看
                return;
            }
            heading = ring.nearest(player.getX(), player.getZ());
            headingY = player.blockPosition().getY();
            leftY = headingY;
            resetProgress();
        }
        double distance = ring.distance(heading, player.getX(), player.getZ());
        if (distance > ON_RING + 1.0) {
            heading = -1;   // 被推离了圈:不再朝原来那列硬走,等回到圈上再接着绕
            stand();
            return;
        }
        if (distance <= ARRIVED && player.onGround()) {
            if (!pickNext()) {
                stand();   // 两头都走不通:站在原地看着
                return;
            }
        } else if (distance < closest - 0.02) {
            closest = distance;
            stalled = 0;
        } else if (++stalled > STUCK_TICKS) {
            // 这一段看着走得通,身体却过不去(有人挡着、碰撞形状比格子判定的窄):掉头回刚才那一列
            heading = Math.floorMod(heading - dir, ring.size());
            dir = -dir;
            headingY = leftY;
            headingJump = leftY > player.blockPosition().getY();
            resetProgress();
        }
        stride();
    }

    /**
     * 站住不走。站着时蹲不蹲由落位批次的高度定,而且每刻都照这个定:落位只在批次刻发生,若别的刻复位成站立,
     * 她会一蹲一起地抖。
     */
    void stand() {
        player.controls().stop();
        player.controls().set(Controls.Key.SNEAK, crouching);
    }

    /**
     * 到了一列,挑下一列:顺着原来的方向走一格;那一段走不过去就掉头;两头都不通返回 false。
     */
    private boolean pickNext() {
        int feetY = player.blockPosition().getY();
        Terrain.Step ahead = stepTo(heading + dir, heading, feetY);
        if (ahead == null) {
            dir = -dir;
            ahead = stepTo(heading + dir, heading, feetY);
            if (ahead == null) {
                return false;
            }
        }
        heading = Math.floorMod(heading + dir, ring.size());
        leftY = feetY;
        headingY = ahead.y();
        headingJump = ahead.jump();
        resetProgress();
        return true;
    }

    /**
     * 从第 {@code from} 列(脚在 {@code feetY})贴地走到相邻的第 {@code to} 列,落脚在哪一层、要不要起跳:站不站得住、迈不迈得
     * 过去都问她身边的地形({@link Terrain#step}),与寻路判一步同一套几何;落脚、托脚、身体经过的格都不是 {@link #KEEP_OFF}
     * 里的。走不过去为 null。再高再深的都不走,那样上得去回不来。
     */
    private Terrain.Step stepTo(int to, int from, int feetY) {
        return Terrain.of(player).step(ring.x(from), ring.z(from), feetY, player.getY(), ring.x(to), ring.z(to),
                KEEP_OFF);
    }

    /**
     * 朝要去的那一列迈步。脸朝哪儿由落位定(看着正在长的那一面),腿按去的方向走:去向相对脸的朝向落在八个方向里的哪一个,
     * 就按哪几个方向键(和真玩家看着一边、按着 WASD 往另一边走一样)。一阵子没放东西,脸转回走的方向。
     */
    private void stride() {
        Vec3 dest = new Vec3(ring.x(heading) + 0.5, headingY, ring.z(heading) + 0.5);
        float pathYaw = (float) (Math.toDegrees(Math.atan2(dest.z - player.getZ(), dest.x - player.getX())) - 90.0);
        if (sinceAim > LOOK_HOLD_TICKS) {
            Aim.turn(player, pathYaw, 12.0f);
        }
        // 去向相对脸的朝向:往前是 cos、往左是 -sin;偏出 22.5° 以外才按侧向的键
        double off = Math.toRadians(Mth.wrapDegrees(pathYaw - player.getYRot()));
        double forward = Math.cos(off);
        double left = -Math.sin(off);
        Controls keys = player.controls();
        keys.set(Controls.Key.FORWARD, forward > DIAGONAL);
        keys.set(Controls.Key.BACK, forward < -DIAGONAL);
        keys.set(Controls.Key.LEFT, left > DIAGONAL);
        keys.set(Controls.Key.RIGHT, left < -DIAGONAL);
        keys.release(Controls.Key.SPRINT);
        // 走着不蹲:潜行不肯走下台阶,还把步子砍到三成
        keys.release(Controls.Key.SNEAK);
        double dx = dest.x - player.getX();
        double dz = dest.z - player.getZ();
        keys.set(Controls.Key.JUMP, headingJump && player.onGround() && dx * dx + dz * dz < 1.3 * 1.3);
    }

    /** 八个方向的分界:去向与一个轴的夹角小于 67.5° 就按那个轴的键。 */
    private static final double DIAGONAL = Math.sin(Math.toRadians(22.5));

    private void resetProgress() {
        closest = Double.MAX_VALUE;
        stalled = 0;
    }

    /**
     * 演出:朝这一批的中心转头、举起对应方块、挥手,方块碎屑与落位声。
     * 粒子按批限量——整栋房子逐格发粒子会把客户端打垮。
     */
    void performWork(List<BlockPos> touched, BlockState sample) {
        Vec3 centre = Vec3.ZERO;
        for (BlockPos pos : touched) {
            centre = centre.add(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
        centre = centre.scale(1.0 / touched.size());
        applySteppedAim(centre);
        sinceAim = 0;
        // 低处蹲下、高处站直:所有人都知道贴边放方块要蹲,这是玩家最熟的建造姿势。
        crouching = centre.y < player.getY() + 0.6;
        // 挥手按动画节拍走,不按落位节拍。原版一轮挥臂约 6 刻,而落位每 2 刻一批
        // ——每批都触发就是每秒十下,手臂永远画不完一个来回,看起来是抽搐不是干活。
        boolean swung = --swingCooldown <= 0;
        if (swung) {
            player.swing(InteractionHand.MAIN_HAND);
            swingCooldown = SWING_PERIOD_TICKS;
        }
        if (sample != null) {
            int slot = inv.findSlot(sample.getBlock().asItem(), true);
            if (slot >= 0) {
                Hotbar.hold(player, slot);
            }
        }
        if (!(player.level() instanceof ServerLevel level) || sample == null) {
            return;
        }
        if (swung) {
            emitConjureTrail(level, centre);
        }
        int spouts = Math.min(PARTICLE_BUDGET_PER_BATCH, touched.size());
        int step = Math.max(1, touched.size() / spouts);
        for (int i = 0; i < touched.size(); i += step) {
            BlockPos pos = touched.get(i);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, sample),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    4, 0.28, 0.28, 0.28, 0.0);
        }
        var sound = sample.getSoundType().getPlaceSound();
        BlockPos at = touched.get(touched.size() / 2);
        level.playSound(null, at, sound, SoundSource.BLOCKS, 0.7f, 0.9f + player.getRandom().nextFloat() * 0.2f);
    }

    /** 收工的一把庆祝粒子,撒在工地正上方。 */
    void celebrate(BlockPos siteMin, BlockPos siteMax) {
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    (siteMin.getX() + siteMax.getX()) / 2.0 + 0.5,
                    siteMax.getY() + 1.0,
                    (siteMin.getZ() + siteMax.getZ()) / 2.0 + 0.5,
                    24, (siteMax.getX() - siteMin.getX()) / 3.0 + 1.0, 1.0,
                    (siteMax.getZ() - siteMin.getZ()) / 3.0 + 1.0, 0.0);
        }
    }

    /**
     * 从她手上飞向落点的一道粒子。
     *
     * <p>方块凭空出现、她在旁边挥手——这两件事之间原本没有任何可见的联系,看着
     * 就像作弊。把因果画出来之后,隔空落位才读得成手艺而不是开挂。一次挥臂一道,
     * 跟着挥臂节拍走,不会刷屏。
     */
    private void emitConjureTrail(ServerLevel level, Vec3 to) {
        Vec3 from = player.getEyePosition().add(player.getLookAngle().scale(0.6)).add(0, -0.3, 0);
        Vec3 step = to.subtract(from).scale(1.0 / (CONJURE_TRAIL_SAMPLES + 1));
        for (int i = 1; i <= CONJURE_TRAIL_SAMPLES; i++) {
            Vec3 p = from.add(step.scale(i));
            level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** 视角按鼠标像素取整转向目标点(和寻路同一套转头,{@link Aim#look})。 */
    private void applySteppedAim(Vec3 point) {
        Aim.look(player, point);
    }
}
