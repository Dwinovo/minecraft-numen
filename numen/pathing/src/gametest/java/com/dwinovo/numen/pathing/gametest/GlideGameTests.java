package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;
import static com.dwinovo.numen.pathing.gametest.Trial.SKY;

import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.api.Travel;
import com.dwinovo.numen.pathing.api.Snapshots;
import com.dwinovo.numen.pathing.drive.Lack;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Kinematics;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * 鞘翅:穿着鞘翅的身体叫了滑翔({@code Travel.GLIDE})就滑翔,不和走路比价钱。用普通假玩家({@link TestBody})经 {@code Navigator} 真走:
 * 平地起飞、进目标 16 格降落、落地就到了;没烟花从高处走出边沿往下滑、没鞘翅或烟花不够当场拒绝、飞着烟花用完迫降后收工、前方突然多出一堵墙(飞得过去就飞过去,
 * 拉不上去就在墙前迫降);另有穿着鞘翅走路(垫柱上升、跳沟)不误展开。
 */
public class GlideGameTests {

    private static final String BATCH = "pathing_glide";
    /** 飞行的中线:场地(宽 64)的正中。降落点上空盘旋的圈半径十四格,场地四周的屏障墙要在圈外。 */
    private static final int MID = 32;

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 穿着鞘翅、背着 {@code rockets} 支烟花的身体,站在 {@code (x, 1, MID)}。 */
    private static TestBody winged(Trial t, int x, int rockets) {
        TestBody body = t.body(x, 1, MID);
        body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        if (rockets > 0) {
            Trial.give(body, new ItemStack(Items.FIREWORK_ROCKET, rockets));
        }
        return body;
    }

    /** 进入目标 16 格才降,降下来落在哪由地面定:落地点离目标水平不超过 16 格再加这么多(盘旋的圈半径 14 加落点那一圈 6 再留余量)。 */
    private static final double GLIDE_ERROR = 24;
    /** 进入目标这么多格才降。 */
    private static final double DESCENT = 16;

    /** 落地(账上最后一段滑翔落地的那一格)离目标那一列的水平距离。 */
    private static double landedFrom(Trial t, Trial.Run r, BlockPos target) {
        BlockPos landed = r.report.ledger().glides().get(r.report.ledger().glides().size() - 1).landing();
        return Math.hypot(landed.getX() - target.getX(), landed.getZ() - target.getZ());
    }

    /** 全程走路的下界(刻):水平距离乘疾跑一格的耗时。 */
    private static double walkBound(TestBody body, double blocks) {
        return blocks * Kinematics.pace(Snapshots.stats(body)).sprint();
    }

    /** 平地一百一十五格外:起飞、爬升、巡航,进目标 16 格降落,落地就到了(不走最后一截);账上有一段滑翔、烟花用了几支、没掉血,落点离目标不超过 16 格加滑翔误差。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 1400, skyAccess = true)
    public static void a_winged_body_takes_off_and_lands_far_away(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 16);
        BlockPos target = t.at(120, 1, MID);
        t.glide(body, Goals.at(target), RouteSpec.defaults()).within(1000)
                .then(r -> {
                    if (landedFrom(t, r, target) > DESCENT + GLIDE_ERROR) {
                        throw new GameTestAssertException("落点离目标 " + landedFrom(t, r, target) + " 格,超过 " + DESCENT + " + " + GLIDE_ERROR);
                    }
                    var glides = r.report.ledger().glides();
                    if (glides.size() != 1) {
                        throw new GameTestAssertException("账上该有一段滑翔,实际 " + glides);
                    }
                    var glide = glides.get(0);
                    if (glide.blocks() < 80 || glide.rockets() < 1 || glide.emergency() || glide.airborne() || glide.damage() > 0) {
                        throw new GameTestAssertException("滑翔的账不对:" + glide);
                    }
                    if (glide.wearAfter() <= glide.wearBefore() && r.ticks > 40) {
                        throw new GameTestAssertException("飞了这么久鞘翅耐久没动:" + glide);
                    }
                    if (r.body.isFallFlying() || r.body.getAbilities().flying) {
                        throw new GameTestAssertException("收场了还在飞");
                    }
                })
                .arrives();
    }

    /** 目标是"离某点三格以内"(范围里有一百多个终点格):照样飞,进目标 16 格降落,落地就到了。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 1400, skyAccess = true)
    public static void a_range_goal_is_flown_to_like_a_cell(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 16);
        BlockPos target = t.at(120, 1, MID);
        t.glide(body, Goals.within(Goals.at(target), 0, 3), RouteSpec.defaults()).within(1000)
                .then(r -> {
                    if (r.report.ledger().glides().size() != 1 || r.report.ledger().glides().get(0).blocks() < 80
                            || landedFrom(t, r, target) > DESCENT + GLIDE_ERROR) {
                        throw new GameTestAssertException("范围目标也该飞,落在目标 16 格附近:" + r.report.ledger().glides());
                    }
                })
                .arrives();
    }

    /** 目标是一整列(只给 x、z):朝那一列飞,进 16 格降落,落在近处的地面上。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 1400, skyAccess = true)
    public static void a_column_goal_is_flown_to_like_a_cell(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 16);
        var cell = t.at(120, 1, MID);
        t.glide(body, Goals.column(cell.getX(), cell.getZ()), RouteSpec.defaults()).within(1000)
                .then(r -> {
                    if (r.report.ledger().glides().size() != 1 || r.report.ledger().glides().get(0).blocks() < 80
                            || landedFrom(t, r, cell) > DESCENT + GLIDE_ERROR) {
                        throw new GameTestAssertException("列目标也该飞,落在目标 16 格附近:" + r.report.ledger().glides());
                    }
                })
                .arrives();
    }

    /**
     * 目标在远处没加载的区块里(沿途也没有加载的:从场地往四个方向、三四百格外起,取第一处从两百格外到目标都没加载的):不因为"沿途有没加载的区块"拒绝飞;
     * 先朝那个水平位置飞,飞到哪加载到哪,进了目标 16 格才选降落点(那片加载了才选得出);账上一段飞了两百多格的滑翔,收场落在地上;四百格的长途比全程走路的下界快(降落时盘旋着沉要花几百刻,短途比走路慢,长途才划算)。
     */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 3000, skyAccess = true)
    public static void a_goal_in_unloaded_chunks_is_flown_and_the_landing_is_found_on_the_way(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        // 站在五十格高的石柱顶上:高过场地四周的屏障和邻居的场地,飞起来不被它们挡
        t.fill(4, 1, MID - 2, 6, 50, MID + 2, Blocks.STONE);
        TestBody body = t.body(5, 51, MID);
        body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        Trial.give(body, new ItemStack(Items.FIREWORK_ROCKET, 24));
        BlockPos start = t.at(5, 51, MID);
        BlockPos goal = null;
        for (int distance = 400; distance <= 900 && goal == null; distance += 100) {
            for (int[] dir : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if (unloadedFrom(body.serverLevel(), start, dir, 200, distance)) {
                    goal = start.offset(dir[0] * distance, -50, dir[1] * distance);
                    break;
                }
            }
        }
        if (goal == null) {
            throw new GameTestAssertException("场地四周找不到没加载的远处,测试场地假设不成立");
        }
        double bound = walkBound(body, 400);
        t.glide(body, Goals.column(goal.getX(), goal.getZ()), RouteSpec.defaults()).within(2500)
                .then(r -> {
                    var glides = r.report.ledger().glides();
                    if (glides.size() != 1 || glides.get(0).blocks() < 250 || glides.get(0).airborne()) {
                        throw new GameTestAssertException("该有一段飞了两百多格的滑翔:" + glides);
                    }
                    if (r.body.isFallFlying() || r.body.getAbilities().flying) {
                        throw new GameTestAssertException("收场了还在飞");
                    }
                    if (r.ticks >= bound) {
                        throw new GameTestAssertException("用了 " + r.ticks + " 刻,没有比走路的下界 " + (int) bound + " 刻快");
                    }
                })
                .arrives();
    }

    /** 从 {@code start} 沿 {@code dir} 走 {@code from} 到 {@code to} 格这一段,每隔十六格取点,点上的区块都没加载。 */
    private static boolean unloadedFrom(ServerLevel level, BlockPos start, int[] dir, int from, int to) {
        for (int d = from; d <= to; d += 16) {
            if (level.getChunkSource().getChunkNow((start.getX() + dir[0] * d) >> 4, (start.getZ() + dir[1] * d) >> 4) != null) {
                return false;
            }
        }
        return true;
    }

    /** 烟花只有一支(又没有边沿可滑):受理时当场拒绝,说烟花不够(有 1 支、要 5 支);开出去的导航也以同一个原因收场,没飞。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 200, skyAccess = true)
    public static void too_few_rockets_refuses_the_flight(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 1);
        var goal = Goals.at(t.at(120, 1, MID));
        Lack lack = t.navigator(body).admit(Travel.GLIDE, goal).lack();
        if (lack == null || lack.why() != Lack.Why.TOO_FEW_ROCKETS || lack.have() != 1 || lack.need() != 5) {
            throw new GameTestAssertException("受理时该说烟花不够(有 1 支,要 5 支):" + lack);
        }
        t.glide(body, goal, RouteSpec.defaults()).within(100)
                .fails(Outcome.Grounded.class, g -> {
                    if (g.lack().why() != Lack.Why.TOO_FEW_ROCKETS) {
                        throw new GameTestAssertException("结局该是烟花不够:" + g.lack());
                    }
                })
                .then(r -> {
                    if (!r.report.ledger().glides().isEmpty() || r.body.isFallFlying()) {
                        throw new GameTestAssertException("烟花不够不该飞:" + r.report.ledger().glides());
                    }
                });
    }

    /** 没穿鞘翅(身上只有烟花)叫了飞:受理时当场拒绝,说没有可用的鞘翅;身体没动。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 200, skyAccess = true)
    public static void flying_without_an_elytra_is_refused(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(5, 1, MID);
        Trial.give(body, new ItemStack(Items.FIREWORK_ROCKET, 8));
        var goal = Goals.at(t.at(120, 1, MID));
        Lack lack = t.navigator(body).admit(Travel.GLIDE, goal).lack();
        if (lack == null || lack.why() != Lack.Why.NO_WINGS) {
            throw new GameTestAssertException("受理时该说没有可用的鞘翅:" + lack);
        }
        t.glide(body, goal, RouteSpec.defaults()).within(100)
                .fails(Outcome.Grounded.class, g -> {
                    if (g.lack().why() != Lack.Why.NO_WINGS) {
                        throw new GameTestAssertException("结局该是没有可用的鞘翅:" + g.lack());
                    }
                });
    }

    /** 烟花低于下限(只剩 4 支)但边沿可滑:不靠烟花起飞,从边沿走出去滑下去(计划里没有烟花)。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 1400, skyAccess = true)
    public static void rockets_below_the_floor_still_glide_off_a_ledge(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(4, 1, MID - 2, 6, 14, MID + 2, Blocks.STONE);
        TestBody body = t.body(5, 15, MID);
        body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        Trial.give(body, new ItemStack(Items.FIREWORK_ROCKET, 4));
        t.glide(body, Goals.at(t.at(85, 1, MID)), RouteSpec.defaults()).within(1000)
                .then(r -> {
                    var glides = r.report.ledger().glides();
                    if (glides.size() != 1 || glides.get(0).emergency() || glides.get(0).damage() > 0
                            || glides.get(0).takeoff().getY() < t.origin.getY() + 10) {
                        throw new GameTestAssertException("该有一段从石柱顶走出去的、没掉血的滑翔:" + glides);
                    }
                })
                .arrives();
    }

    /**
     * 飞着烟花被拿光(身上没了):改降到够得着的平地,不摔;落地时离目标还远、烟花已经不够起飞,这一趟收工(不改成走路把剩下的走完),
     * 结局说烟花不够(有 0 支,要 5 支),账上那一段滑翔是迫降。
     */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 1800, skyAccess = true)
    public static void running_out_of_rockets_in_the_air_ends_in_an_emergency_landing(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 16);
        int[] flying = {0};
        t.glide(body, Goals.at(t.at(130, 1, MID)), RouteSpec.defaults()).within(1500)
                .during(r -> {
                    if (r.body.isFallFlying() && flying[0]++ == 3) {
                        r.body.getInventory().items.replaceAll(stack -> stack.is(Items.FIREWORK_ROCKET) ? ItemStack.EMPTY : stack);
                    }
                })
                .fails(Outcome.Grounded.class, g -> {
                    if (g.lack().why() != Lack.Why.TOO_FEW_ROCKETS || g.lack().have() != 0) {
                        throw new GameTestAssertException("收工的原因该是烟花不够(有 0 支):" + g.lack());
                    }
                })
                .then(r -> {
                    var glides = r.report.ledger().glides();
                    if (glides.size() != 1 || !glides.get(0).emergency() || glides.get(0).damage() > 0) {
                        throw new GameTestAssertException("该有一段迫降且没掉血的滑翔:" + glides);
                    }
                    if (r.lowestHealth < 20) {
                        throw new GameTestAssertException("迫降掉血了:" + r.lowestHealth);
                    }
                    if (r.body.isFallFlying() || !r.body.onGround()) {
                        throw new GameTestAssertException("收工了身体该落在地上");
                    }
                });
    }

    /** 没有烟花,站在十五格高的石柱顶上:从边沿走出去展开,只靠高度滑到七十多格外落地(目标就在那一带),一支烟花也没放,没掉血。脚下没有路下去,只能飞。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 1400, skyAccess = true)
    public static void without_rockets_a_body_glides_off_a_height(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(4, 1, MID - 2, 6, 14, MID + 2, Blocks.STONE);
        TestBody body = t.body(5, 15, MID);
        body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        t.glide(body, Goals.at(t.at(85, 1, MID)), RouteSpec.defaults()).within(1000)
                .then(r -> {
                    var glides = r.report.ledger().glides();
                    if (glides.size() != 1 || glides.get(0).rockets() != 0 || glides.get(0).blocks() < 40 || glides.get(0).damage() > 0
                            || glides.get(0).emergency()) {
                        throw new GameTestAssertException("该有一段没放烟花、没掉血的滑翔:" + glides);
                    }
                })
                .arrives();
    }

    /**
     * 飞到一半前方多出一堵墙(整宽,十六格高,七十格外):线通不通每刻查,被挡就沿航线往前拉升(烟花够),飞过去;不撞上去(不掉血),进目标 16 格降落,到了。
     * 拉升是边往前飞边升,不是停下来原地竖直爬。
     */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 1800, skyAccess = true)
    public static void a_wall_that_appears_ahead_is_flown_over(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 16);
        int[] flying = {0};
        t.glide(body, Goals.at(t.at(130, 1, MID)), RouteSpec.defaults()).within(1500)
                .during(r -> {
                    if (r.body.isFallFlying() && flying[0]++ == 40) {
                        int x = (int) (r.body.getX() - t.origin.getX()) + 70;
                        for (int dy = 1; dy <= 16; dy++) {
                            for (int z = 2; z <= 61; z++) {
                                t.change(x, dy, z, Blocks.STONE.defaultBlockState());
                            }
                        }
                    }
                })
                .then(r -> {
                    if (r.lowestHealth < 20) {
                        throw new GameTestAssertException("撞上了墙或摔了:最低血量 " + r.lowestHealth);
                    }
                    if (r.report.ledger().glides().size() != 1 || r.report.ledger().glides().get(0).emergency()) {
                        throw new GameTestAssertException("该有一段飞过墙、不是迫降的滑翔:" + r.report.ledger().glides());
                    }
                })
                .arrives();
    }

    /**
     * 飞到一半前方多出一堵拉不上去的墙(整宽,一百二十格高,烟花撑不到那么高):就近迫降在墙前,不撞上去(不掉血);落地时离目标不到 96 格,收工,结局说离目标还有多远。
     */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 1800, skyAccess = true)
    public static void a_wall_that_cannot_be_climbed_ends_in_an_emergency_landing_before_it(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 16);
        int[] flying = {0};
        t.glide(body, Goals.at(t.at(130, 1, MID)), RouteSpec.defaults()).within(1500)
                .during(r -> {
                    if (r.body.isFallFlying() && flying[0]++ == 40) {
                        int x = (int) (r.body.getX() - t.origin.getX()) + 44;
                        for (int dy = 1; dy <= 120; dy++) {
                            for (int z = 2; z <= 61; z++) {
                                t.change(x, dy, z, Blocks.STONE.defaultBlockState());
                            }
                        }
                    }
                })
                .fails(Outcome.Grounded.class, g -> {
                    if (g.lack().why() != Lack.Why.SHORT || g.lack().have() <= 0 || g.lack().have() >= 96) {
                        throw new GameTestAssertException("收工的原因该是没进目标范围就落了地、离目标不到 96 格:" + g.lack());
                    }
                })
                .then(r -> {
                    var glides = r.report.ledger().glides();
                    if (glides.size() != 1 || !glides.get(0).emergency() || glides.get(0).damage() > 0 || r.lowestHealth < 20) {
                        throw new GameTestAssertException("该有一段迫降且没掉血的滑翔:" + glides + " 最低血量 " + r.lowestHealth);
                    }
                });
    }

    /** 穿着鞘翅垫柱上升:垫柱在空中起跳,不会因为新按一下跳而展开鞘翅。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void pillaring_up_with_elytra_worn_does_not_unfurl_it(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 0);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 32));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        RouteSpec natural = RouteSpec.defaults().edit().changes(true).consent(false).build();
        t.go(body, Goals.level(t.origin.getY() + 8), natural).within(500)
                .during(r -> {
                    if (r.body.isFallFlying()) {
                        throw new GameTestAssertException("垫柱时误展开了鞘翅,身体在 " + t.rel(r.body.blockPosition()));
                    }
                })
                .arrives();
    }

    /** 穿着鞘翅跳过两格的沟:起跳落地都在地面的步子,空中不重按跳,不误展开。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void jumping_a_gap_with_elytra_worn_does_not_unfurl_it(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(0, 1, 0, 9, 4, 39, Blocks.BEDROCK);
        t.fill(12, 1, 0, 39, 4, 39, Blocks.BEDROCK);
        TestBody body = t.body(6, 5, 5);
        body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        RouteSpec parkour = RouteSpec.defaults().edit().parkour(true).build();
        t.go(body, Goals.at(t.at(14, 5, 5)), parkour).within(300)
                .during(r -> {
                    if (r.body.isFallFlying()) {
                        throw new GameTestAssertException("跳沟时误展开了鞘翅,身体在 " + t.rel(r.body.blockPosition()));
                    }
                })
                .arrives();
    }
}
