package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.api.gametest.Checks;
import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;
import static com.dwinovo.numen.pathing.gametest.Trial.SKY;

import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.api.Travel;
import com.dwinovo.numen.pathing.drive.Lack;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import com.dwinovo.numen.api.gametest.BeforeBatch;
import com.dwinovo.numen.api.gametest.GameTest;
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

    /**
     * 平地四十五格外:起飞、爬升、巡航,进目标 16 格降落,落地就到了(不走最后一截);账上有一段滑翔、烟花用了几支、没掉血,落点离目标不超过 16 格加滑翔误差。
     * 长趟与真实地形的决策在纯 JVM 里跑({@code GlideScenarioTest}),这里只看从入口到身体的这一整条通得过。
     */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 600, skyAccess = true)
    public static void a_winged_body_takes_off_and_lands_on_flat_ground(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 16);
        BlockPos target = t.at(50, 1, MID);
        t.glide(body, Goals.at(target), RouteSpec.defaults()).within(500)
                .then(r -> {
                    if (landedFrom(t, r, target) > DESCENT + GLIDE_ERROR) {
                        throw Checks.failure("落点离目标 " + landedFrom(t, r, target) + " 格,超过 " + DESCENT + " + " + GLIDE_ERROR);
                    }
                    var glides = r.report.ledger().glides();
                    if (glides.size() != 1) {
                        throw Checks.failure("账上该有一段滑翔,实际 " + glides);
                    }
                    var glide = glides.get(0);
                    if (glide.blocks() < 20 || glide.rockets() < 1 || glide.emergency() || glide.airborne() || glide.damage() > 0) {
                        throw Checks.failure("滑翔的账不对:" + glide);
                    }
                    if (glide.wearAfter() <= glide.wearBefore() && r.ticks > 40) {
                        throw Checks.failure("飞了这么久鞘翅耐久没动:" + glide);
                    }
                    if (r.body.isFallFlying() || r.body.getAbilities().flying) {
                        throw Checks.failure("收场了还在飞");
                    }
                })
                .arrives();
    }

    /** 目标是"离某点三格以内"(范围里有一百多个终点格):照样飞,进目标 16 格降落,落地就到了。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 600, skyAccess = true)
    public static void a_range_goal_is_flown_to_like_a_cell(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 16);
        BlockPos target = t.at(50, 1, MID);
        t.glide(body, Goals.within(Goals.at(target), 0, 3), RouteSpec.defaults()).within(500)
                .then(r -> {
                    if (r.report.ledger().glides().size() != 1 || r.report.ledger().glides().get(0).blocks() < 20
                            || landedFrom(t, r, target) > DESCENT + GLIDE_ERROR) {
                        throw Checks.failure("范围目标也该飞,落在目标 16 格附近:" + r.report.ledger().glides());
                    }
                })
                .arrives();
    }

    /** 目标是一整列(只给 x、z):朝那一列飞,进 16 格降落,落在近处的地面上。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 600, skyAccess = true)
    public static void a_column_goal_is_flown_to_like_a_cell(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 16);
        var cell = t.at(50, 1, MID);
        t.glide(body, Goals.column(cell.getX(), cell.getZ()), RouteSpec.defaults()).within(500)
                .then(r -> {
                    if (r.report.ledger().glides().size() != 1 || r.report.ledger().glides().get(0).blocks() < 20
                            || landedFrom(t, r, cell) > DESCENT + GLIDE_ERROR) {
                        throw Checks.failure("列目标也该飞,落在目标 16 格附近:" + r.report.ledger().glides());
                    }
                })
                .arrives();
    }

    /** 烟花只有一支(又没有边沿可滑):受理时当场拒绝,说烟花不够(有 1 支、要 5 支);开出去的导航也以同一个原因收场,没飞。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 200, skyAccess = true)
    public static void too_few_rockets_refuses_the_flight(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 1);
        var goal = Goals.at(t.at(120, 1, MID));
        Lack lack = t.navigator(body).admit(Travel.GLIDE, goal).lack();
        if (lack == null || lack.why() != Lack.Why.TOO_FEW_ROCKETS || lack.have() != 1 || lack.need() != 5) {
            throw Checks.failure("受理时该说烟花不够(有 1 支,要 5 支):" + lack);
        }
        t.glide(body, goal, RouteSpec.defaults()).within(100)
                .fails(Outcome.Grounded.class, g -> {
                    if (g.lack().why() != Lack.Why.TOO_FEW_ROCKETS) {
                        throw Checks.failure("结局该是烟花不够:" + g.lack());
                    }
                })
                .then(r -> {
                    if (!r.report.ledger().glides().isEmpty() || r.body.isFallFlying()) {
                        throw Checks.failure("烟花不够不该飞:" + r.report.ledger().glides());
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
            throw Checks.failure("受理时该说没有可用的鞘翅:" + lack);
        }
        t.glide(body, goal, RouteSpec.defaults()).within(100)
                .fails(Outcome.Grounded.class, g -> {
                    if (g.lack().why() != Lack.Why.NO_WINGS) {
                        throw Checks.failure("结局该是没有可用的鞘翅:" + g.lack());
                    }
                });
    }

    /** 烟花低于下限(只剩 4 支)但边沿可滑:不靠烟花起飞,从边沿走出去滑下去(计划里没有烟花)。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 600, skyAccess = true)
    public static void rockets_below_the_floor_still_glide_off_a_ledge(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(4, 1, MID - 2, 6, 14, MID + 2, Blocks.STONE);
        TestBody body = t.body(5, 15, MID);
        body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        Trial.give(body, new ItemStack(Items.FIREWORK_ROCKET, 4));
        t.glide(body, Goals.at(t.at(85, 1, MID)), RouteSpec.defaults()).within(500)
                .then(r -> {
                    var glides = r.report.ledger().glides();
                    if (glides.size() != 1 || glides.get(0).emergency() || glides.get(0).damage() > 0
                            || glides.get(0).takeoff().getY() < t.origin.getY() + 10) {
                        throw Checks.failure("该有一段从石柱顶走出去的、没掉血的滑翔:" + glides);
                    }
                })
                .arrives();
    }

    /** 没有烟花,站在十五格高的石柱顶上:从边沿走出去展开,只靠高度滑到七十多格外落地(目标就在那一带),一支烟花也没放,没掉血。脚下没有路下去,只能飞。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 600, skyAccess = true)
    public static void without_rockets_a_body_glides_off_a_height(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(4, 1, MID - 2, 6, 14, MID + 2, Blocks.STONE);
        TestBody body = t.body(5, 15, MID);
        body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        t.glide(body, Goals.at(t.at(85, 1, MID)), RouteSpec.defaults()).within(500)
                .then(r -> {
                    var glides = r.report.ledger().glides();
                    if (glides.size() != 1 || glides.get(0).rockets() != 0 || glides.get(0).blocks() < 40 || glides.get(0).damage() > 0
                            || glides.get(0).emergency()) {
                        throw Checks.failure("该有一段没放烟花、没掉血的滑翔:" + glides);
                    }
                })
                .arrives();
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
                        throw Checks.failure("垫柱时误展开了鞘翅,身体在 " + t.rel(r.body.blockPosition()));
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
                        throw Checks.failure("跳沟时误展开了鞘翅,身体在 " + t.rel(r.body.blockPosition()));
                    }
                })
                .arrives();
    }
}
