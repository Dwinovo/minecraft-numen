package com.dwinovo.numen.pathing.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.world.Kinematics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 滑翔的长趟,纯 JVM:真实的 {@link Glide}(受理、选落点、拉升)、{@link Landings}、{@link Sortie}(起飞、巡航、降落、迫降、卡住)与 {@link Pilot}、{@link Helm},
 * 身体是 {@link Glider}(同一份 {@link Dynamics} 步进器逐刻推),地形是 {@link Land}(固定参数、固定种子摆出来的树林、丘陵、山脊、池塘,每次逐格相同)。
 * 断言的是决策的结果:落点离目标不超过 {@value #DESCENT} 加滑翔误差 {@value #GLIDE_ERROR}、落在天然的地面上(不在树冠上、不在水里、不在边沿上)、不掉血、
 * 没进目标范围就落地时再起飞或收工;每个场景换六个烟花随机种子各跑一遍(原版烟花每支多推几刻是随机的),对哪种推法都得成立。起飞点、走到起飞点({@link Voyage} 的走路段)不在这里:身体直接放在 {@link Glide#admit} 挑的起飞点上。
 * 我们的推算和原版物理对不对得上,由 GameTest 里的对照用例守。
 */
class GlideScenarioTest {

    /** 进入目标这么多格才降;降下来落在哪由地面定,落点离目标水平不超过它再加这么多(盘旋的圈半径 14 加落点那一圈 6 再留余量)。 */
    private static final double DESCENT = 16;
    private static final double GLIDE_ERROR = 24;
    /** 飞行的中线。 */
    private static final int MID = 32;
    private static final int LIMIT = 4000;

    /** 墙与山脊"整宽":场地的宽(0 到 63)再加四周铺的地,绕不过去。 */
    private static final int WEST = -Land.MARGIN;
    private static final int EAST = 63 + Land.MARGIN;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    // ==================== 跑法 ====================

    /** 每刻身体走完之后看一眼、动动世界。 */
    private interface Hook {
        void after(int tick, Glider body);
    }

    /** 一次出行:身体、世界、已经用过的起飞点;{@link #launch} 受理并起飞一段,{@link #fly} 飞到这一段收场。 */
    private static final class Trip {

        final Land land;
        final Glider body;
        final Set<BlockPos> spent = new HashSet<>();
        Sortie sortie;
        Glide.Plan plan;
        int ticks;

        Trip(Land land, Glider body) {
            this.land = land;
            this.body = body;
        }

        /** 受理(必须受理)并从挑中的起飞点起一段。 */
        Trip launch(Goal goal) {
            Admission asked = Glide.admit(body, goal, spent);
            assertNull(asked.lack(), "受理不了:" + asked.lack());
            plan = asked.plan();
            body.standAt(plan.takeoff());
            sortie = new Sortie(body, plan);
            return this;
        }

        /** 这一段飞到收场(落地或停下);{@code limit} 刻内没收场就是失败。 */
        Trip fly(Hook hook, int limit) {
            int run = 0;
            while (sortie.state() == Driver.State.RUNNING && run++ < limit) {
                sortie.tick();
                body.step();
                if (hook != null) {
                    hook.after(ticks, body);
                }
                ticks++;
            }
            assertTrue(sortie.state() != Driver.State.RUNNING, "这一段 " + limit + " 刻里没有收场:" + body.describe());
            return this;
        }

        Trip fly() {
            return fly(null, LIMIT);
        }

        /** 账上最后一段滑翔。 */
        EditLedger.Glided last() {
            List<EditLedger.Glided> glides = body.ledger().glides();
            return glides.get(glides.size() - 1);
        }

        /** 最后一段落地(账上记的那一格)离 {@code (x, z)} 的水平距离。 */
        double apart(int x, int z) {
            BlockPos landed = last().landing();
            return Math.hypot(landed.getX() - x, landed.getZ() - z);
        }
    }

    private static Land flat(int length) {
        return Land.flat(length, 64, 2);
    }

    /** 站在 {@code (x, MID)} 的地面上的身体,背着 {@code rockets} 支烟花。 */
    private static Glider winged(Land land, int x, int rockets, long seed) {
        return new Glider(land, new Vec3(x + 0.5, land.ground(x, MID) + 1, MID + 0.5), 0, rockets, seed);
    }

    private static Trip trip(Land land, int rockets, long seed) {
        return new Trip(land, winged(land, 6, rockets, seed));
    }

    /** 全程走路的下界(刻):水平距离乘疾跑一格的耗时。 */
    private static double walkBound(double blocks) {
        return blocks * Kinematics.pace(Vanilla.SURVIVAL).sprint();
    }

    /**
     * 一趟滑翔安全落地共同的几条:落在天然的地面上(脚下不是树叶、不在液体里)、头顶没有东西(最高碰撞面就是脚)、一路满血、
     * 落点离目标不超过 {@value #DESCENT} 加 {@value #GLIDE_ERROR}。
     */
    private static void assertSafeLanding(Trip t, int targetX, int targetZ) {
        BlockPos landed = t.last().landing();
        assertTrue(Landings.natural(t.land.getBlockState(landed.below())), "落在 " + t.land.getBlockState(landed.below()).getBlock() + " 上 " + landed);
        assertTrue(t.land.getFluidState(landed).isEmpty() && t.land.getFluidState(landed.below()).isEmpty(), "落在液体里 " + landed);
        assertTrue(t.land.top(landed.getX(), landed.getZ()) <= landed.getY(), "落在东西底下 " + landed);
        assertTrue(t.body.lowestHealth() >= 20, "掉血了,最低 " + t.body.lowestHealth() + t.body.hurts());
        double apart = t.apart(targetX, targetZ);
        assertTrue(apart <= DESCENT + GLIDE_ERROR, "落点离目标 " + apart + " 格,超过 " + DESCENT + " + " + GLIDE_ERROR);
    }

    /** 落点站得住:周围八列的地面与它差不到两格(不在边沿上)。 */
    private static void assertNotOnAnEdge(Trip t) {
        BlockPos landed = t.last().landing();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int top = t.land.top(landed.getX() + dx, landed.getZ() + dz);
                assertTrue(Math.abs(top - landed.getY()) <= 1, "落在边沿上 " + landed + ",旁边 " + dx + "," + dz + " 的地面在 " + top);
            }
        }
    }

    /** 进了目标范围、降到落地:这一趟到了,账上就这一段滑翔、不是迫降、没悬在空中。 */
    private static void assertArrived(Trip t) {
        assertEquals(Driver.State.ARRIVED, t.sortie.state());
        assertTrue(t.sortie.descended(), "没进过目标范围就落了地");
        assertEquals(1, t.body.ledger().glides().size());
        EditLedger.Glided glide = t.last();
        assertFalse(glide.emergency() || glide.airborne() || glide.trapped(), "账不对:" + glide);
        assertFalse(t.body.gliding());
    }

    // ==================== 真实地形 ====================

    /** 目标在树林边:树林(树干五六格、树冠连成一片)在飞行线上,目标在树林东边的草地上,最后一排树离它几格。落点不在树冠上,不掉血。 */
    @ParameterizedTest(name = "烟花随机种子 {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5})
    void aGlideToTheEdgeOfAGroveLandsInTheOpen(long seed) {
        Land land = flat(208).forest(96, 6, 130, 58, 3, 11, null);
        Trip t = trip(land, 14, seed).launch(Goals.column(140, MID)).fly();
        assertArrived(t);
        assertSafeLanding(t, 140, MID);
    }

    /**
     * 目标在树下:七十格宽的树林,林中一块九格见方的空地,目标那一列在空地东边十格的树冠下。树冠上、树冠下都不是落点(最高碰撞面是树叶),
     * 落在空地或林外的草地上,不在树冠上、不掉血。
     */
    @ParameterizedTest(name = "烟花随机种子 {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5})
    void aGlideToAColumnUnderTheTreesLandsInTheOpen(long seed) {
        Land land = flat(208).forest(100, 4, 170, 60, 3, 23, new int[] {124, 132, 28, 36});
        Trip t = trip(land, 14, seed).launch(Goals.column(142, MID)).fly();
        assertArrived(t);
        assertSafeLanding(t, 142, MID);
    }

    /** 丘陵:起伏六格、坡度四成,目标在远处的坡上。落点站得住:脚下天然、周围八列差不到两格(不在边沿上),落地不掉血。 */
    @ParameterizedTest(name = "烟花随机种子 {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5})
    void aGlideOverHillsLandsOnLevelGroundNotOnAnEdge(long seed) {
        Land land = Land.hills(208, 64, 1, 6);
        Glider body = new Glider(land, new Vec3(6.5, land.ground(6, MID) + 1, MID + 0.5), 0, 14, seed);
        Trip t = new Trip(land, body).launch(Goals.column(150, MID)).fly();
        assertArrived(t);
        assertSafeLanding(t, 150, MID);
        assertNotOnAnEdge(t);
    }

    /** 起飞点在树林边:树林在身后不远处,树冠比头顶高;起飞、飞到目标附近落在空地上,一段飞了八十多格的滑翔,没掉血。 */
    @ParameterizedTest(name = "烟花随机种子 {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5})
    void aGlideTakesOffBesideAGrove(long seed) {
        Land land = flat(208).forest(10, 20, 24, 44, 3, 5, null);
        Trip t = trip(land, 12, seed).launch(Goals.column(135, MID)).fly();
        assertArrived(t);
        assertSafeLanding(t, 135, MID);
        assertTrue(t.last().blocks() > 80, "该飞得远:" + t.last());
    }

    /** 中距离翻山:一道二十二格高、十一格厚的山脊横在路上,巡航高度取山脊最高处再加十六格,飞过去,进目标 16 格降落。 */
    @ParameterizedTest(name = "烟花随机种子 {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5})
    void aGlideGoesOverARidge(long seed) {
        Land land = flat(208).fill(50, 3, WEST, 60, 24, EAST, Blocks.STONE.defaultBlockState());
        Trip t = trip(land, 16, seed).launch(Goals.column(125, MID)).fly();
        assertArrived(t);
        assertSafeLanding(t, 125, MID);
    }

    /** 目标在水边:一片池塘,目标是池塘中间。落点不在液体里(落在池边的岸上)。 */
    @ParameterizedTest(name = "烟花随机种子 {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5})
    void aGlideToAPondLandsOnTheShoreNotInTheWater(long seed) {
        Land land = Land.flat(208, 64, 4).fill(110, 3, 20, 130, 4, 44, Blocks.WATER.defaultBlockState());
        Trip t = trip(land, 14, seed).launch(Goals.column(120, MID)).fly();
        assertArrived(t);
        assertSafeLanding(t, 120, MID);
    }

    // ==================== 进目标范围、落地即结束 ====================

    /**
     * 平地一百一十五格外:起飞、爬升、巡航,进目标 16 格降落,落地就到了(不再走一截、不再起飞):账上一段滑翔、烟花用了几支、没掉血、鞘翅耐久动了,
     * 落点离目标不超过 16 格加滑翔误差,比全程走路的下界快。
     */
    @ParameterizedTest(name = "烟花随机种子 {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5})
    void aFlightOverFlatGroundLandsOnceInsideTheRangeAndBeatsWalking(long seed) {
        Trip t = trip(flat(160), 16, seed).launch(Goals.column(121, MID)).fly();
        assertArrived(t);
        assertSafeLanding(t, 121, MID);
        EditLedger.Glided glide = t.last();
        assertTrue(glide.blocks() >= 80 && glide.rockets() >= 1, "账不对:" + glide);
        assertTrue(glide.wearAfter() >= glide.wearBefore(), "鞘翅耐久倒着走:" + glide);
        double bound = walkBound(115);
        assertTrue(t.ticks < bound, "用了 " + t.ticks + " 刻,没有比走路的下界 " + (int) bound + " 刻快");
    }

    /**
     * 四百格的长途,目标那一片一开始没加载(飞到哪加载到哪,进了目标范围才选得出落点):不因为沿途有没加载的区块拒绝飞,账上一段飞了两百多格的滑翔,
     * 落在地上,比全程走路的下界快。
     */
    @ParameterizedTest(name = "烟花随机种子 {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5})
    void aFourHundredBlockGlideOverUnloadedChunksIsFasterThanWalking(long seed) {
        Land land = Land.flat(448, 64, 2).streaming();
        Glider body = winged(land, 6, 24, seed);
        land.follow(body.position(), 8);
        Trip t = new Trip(land, body).launch(Goals.column(406, MID)).fly(null, 6000);
        assertArrived(t);
        assertSafeLanding(t, 406, MID);
        assertTrue(t.last().blocks() > 250, "该飞两百多格:" + t.last());
        double bound = walkBound(400);
        assertTrue(t.ticks < bound, "用了 " + t.ticks + " 刻,没有比走路的下界 " + (int) bound + " 刻快");
    }

    // ==================== 没进目标范围就落了地 ====================

    /**
     * 身体飞到 {@code fromX} 之后(已经离开起飞的空柱、在巡航),前方 {@code ahead} 格外砌起一堵二百五十格高的整宽墙;{@code removeAfter} 刻之后撤掉(0 是不撤)。
     * 按位置而不是按飞了几刻:爬升快慢随烟花的随机,同样的刻数身体可能还在柱子里,迫降就落在起飞点边上,离目标远近就不是这个场景要说的了。
     */
    private static Hook tallWall(Land land, int ahead, int fromX, int removeAfter) {
        int[] built = {-1};
        int[] at = {0};
        return (tick, body) -> {
            if (built[0] < 0 && body.gliding() && body.position().x >= fromX) {
                at[0] = (int) body.position().x + ahead;
                land.fill(at[0], 3, WEST, at[0] + 1, 252, EAST, Blocks.STONE.defaultBlockState());
                built[0] = tick;
            }
            if (removeAfter > 0 && built[0] >= 0 && tick == built[0] + removeAfter) {
                land.fill(at[0], 3, WEST, at[0] + 1, 252, EAST, Blocks.AIR.defaultBlockState());
            }
        };
    }

    /**
     * 迫降之后离目标还有一百多格(超过 96)、烟花够:从落地的地方再起飞,飞到目标附近降落。账上两段滑翔,第一段是迫降,没进过目标范围。
     * 再起飞的判据是 {@link Glide#REFLY_RANGE}(行程 {@link Voyage} 比的就是它);这里照它再受理、再起一段。
     */
    @ParameterizedTest(name = "烟花随机种子 {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5})
    void aGlideThatLandsShortAndFarTakesOffAgain(long seed) {
        Land land = flat(208);
        Trip t = trip(land, 24, seed).launch(Goals.column(195, MID));
        t.fly(tallWall(land, 60, 40, 12), LIMIT);
        assertEquals(Driver.State.ARRIVED, t.sortie.state());
        assertFalse(t.sortie.descended(), "第一段没进过目标范围");
        assertTrue(t.last().emergency(), "第一段该是迫降:" + t.last());
        assertTrue(t.body.lowestHealth() >= 20, "迫降掉血了:" + t.body.lowestHealth());
        double left = Math.hypot(t.body.position().x - 195.5, t.body.position().z - (MID + 0.5));
        assertTrue(left > Glide.REFLY_RANGE, "落地时离目标 " + left + " 格,该超过 " + Glide.REFLY_RANGE);
        t.spent.add(t.sortie.takeoffCell());
        t.launch(Goals.column(195, MID)).fly();
        assertEquals(Driver.State.ARRIVED, t.sortie.state());
        assertTrue(t.sortie.descended());
        assertEquals(2, t.body.ledger().glides().size());
        assertSafeLanding(t, 195, MID);
    }

    /** 迫降之后离目标不到 96 格:墙不撤,就近迫降在墙前;离目标只有几十格,不再起飞(行程收工,说离目标还有多远)。账上那一段是迫降、没掉血。 */
    @ParameterizedTest(name = "烟花随机种子 {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5})
    void aGlideThatLandsShortAndNearStopsWithTheDistanceLeft(long seed) {
        Land land = flat(208);
        Trip t = trip(land, 24, seed).launch(Goals.column(105, MID));
        t.fly(tallWall(land, 40, 40, 0), LIMIT);
        assertEquals(Driver.State.ARRIVED, t.sortie.state());
        assertFalse(t.sortie.descended());
        assertEquals(1, t.body.ledger().glides().size());
        assertTrue(t.last().emergency() && t.last().damage() == 0 && t.body.lowestHealth() >= 20, "该是没掉血的迫降:" + t.last());
        double left = Math.hypot(t.body.position().x - 105.5, t.body.position().z - (MID + 0.5));
        assertTrue(left > DESCENT && left < Glide.REFLY_RANGE, "离目标该还有 16 到 96 格,实际 " + left);
        assertTrue(t.body.onGround() && !t.body.gliding());
    }

    /** 飞着烟花被拿光(身上没了):改降到够得着的平地,不摔;账上那一段是迫降。烟花不够起飞了,行程收工。 */
    @ParameterizedTest(name = "烟花随机种子 {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5})
    void runningOutOfRocketsInTheAirEndsInAnEmergencyLanding(long seed) {
        Land land = flat(208);
        Trip t = trip(land, 16, seed).launch(Goals.column(130, MID));
        int[] flying = {0};
        t.fly((tick, body) -> {
            if (body.gliding() && flying[0]++ == 3) {
                body.loseRockets();
            }
        }, LIMIT);
        assertEquals(Driver.State.ARRIVED, t.sortie.state());
        assertFalse(t.sortie.descended());
        assertTrue(t.last().emergency() && t.last().damage() == 0, "该有一段没掉血的迫降:" + t.last());
        assertTrue(t.body.lowestHealth() >= 20, "迫降掉血了:" + t.body.lowestHealth());
        assertTrue(t.body.mobility().fireworks() < Glide.ROCKET_FLOOR, "烟花该不够起飞");
        assertTrue(t.body.onGround() && !t.body.gliding());
    }

    // ==================== 落点与前方的东西 ====================

    /**
     * 降落途中选好的落点落不下了:进了目标范围、降下来的时候,身体正下方那一列被人砌起一根九格高的圆石柱(柱顶不是天然方块,那一列也不再是最高碰撞面);
     * 换一个安全的落点,不落在柱子上、不掉血。
     */
    @ParameterizedTest(name = "烟花随机种子 {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5})
    void aLandingSpotTakenOnTheWayDownIsSwapped(long seed) {
        Land land = flat(208);
        Trip t = trip(land, 14, seed).launch(Goals.column(130, MID));
        BlockPos[] pillar = {null};
        t.fly((tick, body) -> {
            if (pillar[0] == null && body.gliding() && Math.hypot(body.position().x - 130.5, body.position().z - (MID + 0.5)) < DESCENT - 6) {
                pillar[0] = body.cell();
                land.fill(pillar[0].getX(), 3, pillar[0].getZ(), pillar[0].getX(), 11, pillar[0].getZ(), Blocks.COBBLESTONE.defaultBlockState());
            }
        }, LIMIT);
        assertNotNull(pillar[0], "柱子没砌");
        assertArrived(t);
        assertSafeLanding(t, 130, MID);
        BlockPos landed = t.last().landing();
        assertTrue(landed.getX() != pillar[0].getX() || landed.getZ() != pillar[0].getZ(), "落在了柱子上");
    }

    /** 飞到一半前方多出一堵整宽、二十二格高的墙(七十格外):每刻查线通不通,被挡就沿航线往前拉升(烟花够),飞过去;不撞上去,进目标 16 格降落。 */
    @ParameterizedTest(name = "烟花随机种子 {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5})
    void aWallThatAppearsAheadIsFlownOver(long seed) {
        Land land = flat(208);
        Trip t = trip(land, 16, seed).launch(Goals.column(130, MID));
        int[] flying = {0};
        t.fly((tick, body) -> {
            if (body.gliding() && flying[0]++ == 40) {
                int x = (int) body.position().x + 70;
                land.fill(x, 3, WEST, x, 24, EAST, Blocks.STONE.defaultBlockState());
            }
        }, LIMIT);
        assertArrived(t);
        assertSafeLanding(t, 130, MID);
    }

    /** 飞到一半前方多出一堵拉不上去的墙(一百二十格高,烟花撑不到那么高):就近迫降在墙前,不撞上去(不掉血);离目标不到 96 格,行程收工。 */
    @ParameterizedTest(name = "烟花随机种子 {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5})
    void aWallThatCannotBeClimbedEndsInAnEmergencyLandingBeforeIt(long seed) {
        Land land = flat(208);
        Trip t = trip(land, 16, seed).launch(Goals.column(130, MID));
        int[] wall = {0};
        t.fly((tick, body) -> {
            if (body.gliding() && wall[0] == 0 && body.position().x >= 40) {
                wall[0] = (int) body.position().x + 44;
                land.fill(wall[0], 3, WEST, wall[0], 123, EAST, Blocks.STONE.defaultBlockState());
            }
        }, LIMIT);
        assertEquals(Driver.State.ARRIVED, t.sortie.state());
        assertFalse(t.sortie.descended());
        assertTrue(t.last().emergency() && t.last().damage() == 0 && t.body.lowestHealth() >= 20, "该是没掉血的迫降:" + t.last() + " 最低血量 " + t.body.lowestHealth());
        assertTrue(t.body.position().x < wall[0], "该落在墙前");
        double left = Math.hypot(t.body.position().x - 130.5, t.body.position().z - (MID + 0.5));
        assertTrue(left > 0 && left < Glide.REFLY_RANGE, "离目标该不到 96 格:" + left);
    }

    /**
     * 爬到一半头顶多出树冠(九格见方、头顶三格):起飞点那根柱子是空的,烟花把身体往上推、顶在树叶下面。控制器每一组推演都撞树叶:被围住就改降(迫降),
     * 不是顶着树叶一直放烟花;这一趟在有限的刻数里有了结局,账上第一段是迫降、烟花只用了几支、没掉血;落点离航线(起飞点到目标那条线)的偏离有界。
     */
    @ParameterizedTest(name = "烟花随机种子 {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5})
    void aClimbIntoACanopyComesDownInsteadOfFiringOn(long seed) {
        Land land = flat(208);
        Trip t = trip(land, 24, seed).launch(Goals.column(180, MID));
        int[] flying = {0};
        int[] roofAt = {-1};
        t.fly((tick, body) -> {
            if (roofAt[0] < 0 && body.gliding() && ++flying[0] == 8) {
                int y = body.cell().getY() + 3;
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        land.set(body.cell().getX() + dx, y, body.cell().getZ() + dz, Blocks.OAK_LEAVES.defaultBlockState());
                    }
                }
                roofAt[0] = tick;
            }
        }, 1200);
        assertTrue(roofAt[0] >= 0, "树冠没搭");
        assertTrue(t.last().emergency(), "第一段该是迫降:" + t.last());
        assertTrue(t.last().rockets() <= 6, "一直往树叶里放烟花:" + t.last());
        assertTrue(t.body.lowestHealth() >= 20, "掉血了:" + t.body.lowestHealth());
        double aside = Math.abs(t.last().landing().getZ() - MID);
        assertTrue(aside <= 30, "落点偏出航线 " + aside + " 格");
    }

    // ==================== 被方块顶住 ====================

    /**
     * 滑翔中被树叶困住:飞出去之后身边多出一团实心的树叶(五乘五乘五,只留身体那一格),烟花推着身体顶在上面一动不动(悬在原地来代替"推力与撞上去的反力正好抵消")。
     * 滑翔每刻问的是身体离开原地没有(走路卡住的同一个看门狗):一秒不动就收工,不再放烟花,交出顶着身体的那一格与方块,放了几支烟花记在账上,不替她挖。
     */
    @ParameterizedTest(name = "烟花随机种子 {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5})
    void aGlideHeldByLeavesStopsAndSaysWhatHoldsIt(long seed) {
        Land land = flat(208);
        Trip t = trip(land, 24, seed).launch(Goals.column(150, MID));
        int[] flying = {0};
        int[] boxedAt = {-1};
        t.fly((tick, body) -> {
            if (boxedAt[0] < 0 && body.gliding() && ++flying[0] == 60) {
                BlockPos at = body.cell();
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            if (dx != 0 || dy != 0 || dz != 0) {
                                land.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz, Blocks.OAK_LEAVES.defaultBlockState());
                            }
                        }
                    }
                }
                body.standAt(at);
                body.pinned();
                boxedAt[0] = tick;
            }
        }, 1500);
        assertTrue(boxedAt[0] >= 0, "没被困住");
        assertEquals(Driver.State.HALTED, t.sortie.state());
        assertTrue(t.sortie.trapped());
        assertTrue(t.ticks - boxedAt[0] <= 100, "用了 " + (t.ticks - boxedAt[0]) + " 刻才放弃");
        Halt.Blocked held = assertInstanceOf(Halt.Blocked.class, t.sortie.halt());
        assertEquals(Blocks.OAK_LEAVES, held.blockage().block().getBlock());
        assertEquals(Blockage.Hitch.PINNED, held.blockage().hitch());
        assertTrue(t.last().trapped() && t.last().rockets() <= 8, "账不对:" + t.last());
    }
}
