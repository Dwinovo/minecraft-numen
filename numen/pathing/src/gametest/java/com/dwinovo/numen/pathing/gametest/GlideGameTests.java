package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;
import static com.dwinovo.numen.pathing.gametest.Trial.SKY;

import com.dwinovo.numen.pathing.api.Snapshots;
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
 * 鞘翅:穿着鞘翅、背着烟花的身体去很远的地方,用不用滑翔由估计的耗时定(比走路的下界快才飞)。用普通假玩家({@link TestBody})经 {@code Navigator} 真走:
 * 平地起飞到远处降落、没烟花从高处走出边沿往下滑、烟花不够就走路、飞着烟花用完迫降、前方突然多出一堵墙;另有穿着鞘翅走路(垫柱上升、跳沟)不误展开。
 */
public class GlideGameTests {

    private static final String BATCH = "pathing_glide";

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 穿着鞘翅、背着 {@code rockets} 支烟花的身体,站在 {@code (x, 1, 12)}。 */
    private static TestBody winged(Trial t, int x, int rockets) {
        TestBody body = t.body(x, 1, 12);
        body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        if (rockets > 0) {
            Trial.give(body, new ItemStack(Items.FIREWORK_ROCKET, rockets));
        }
        return body;
    }

    /** 全程走路的下界(刻):水平距离乘疾跑一格的耗时。 */
    private static double walkBound(TestBody body, double blocks) {
        return blocks * Kinematics.pace(Snapshots.stats(body)).sprint();
    }

    /** 平地一百一十五格外:起飞、爬升、巡航、下滑,落在目标这一侧,走完最后一截;比全程走路的下界快,账上有一段滑翔、烟花用了几支、没掉血。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 1400)
    public static void a_winged_body_takes_off_and_lands_far_away(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 8);
        double bound = walkBound(body, 115);
        t.go(body, Goals.at(t.at(120, 1, 12)), RouteSpec.defaults()).within(1000)
                .then(r -> {
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
                    if (r.ticks >= bound) {
                        throw new GameTestAssertException("用了 " + r.ticks + " 刻,没有比走路的下界 " + (int) bound + " 刻快");
                    }
                    if (r.body.isFallFlying() || r.body.getAbilities().flying) {
                        throw new GameTestAssertException("收场了还在飞");
                    }
                })
                .arrives();
    }

    /** 目标是"离某点三格以内"(范围里有一百多个终点格):照样飞,落在目标这一侧,走完最后一截。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 1400)
    public static void a_range_goal_is_flown_to_like_a_cell(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 8);
        t.go(body, Goals.within(Goals.at(t.at(120, 1, 12)), 0, 3), RouteSpec.defaults()).within(1000)
                .then(r -> {
                    if (r.report.ledger().glides().size() != 1 || r.report.ledger().glides().get(0).blocks() < 80) {
                        throw new GameTestAssertException("范围目标也该飞:" + r.report.ledger().glides());
                    }
                })
                .arrives();
    }

    /** 目标是一整列(只给 x、z):朝那一列飞,落在近处的地面上,走到那一列上。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 1400)
    public static void a_column_goal_is_flown_to_like_a_cell(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 8);
        var cell = t.at(120, 1, 12);
        t.go(body, Goals.column(cell.getX(), cell.getZ()), RouteSpec.defaults()).within(1000)
                .then(r -> {
                    if (r.report.ledger().glides().size() != 1 || r.report.ledger().glides().get(0).blocks() < 80) {
                        throw new GameTestAssertException("列目标也该飞:" + r.report.ledger().glides());
                    }
                })
                .arrives();
    }

    /**
     * 目标在远处没加载的区块里(沿途也没有加载的:从场地往四个方向、三四百格外起,取第一处从两百格外到目标都没加载的):不因为"沿途有没加载的区块"拒绝飞;
     * 先朝那个水平位置飞,飞到哪加载到哪,那片加载了再定降落点;账上一段飞了两百多格的滑翔,收场落在地上。
     */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 3000)
    public static void a_goal_in_unloaded_chunks_is_flown_and_the_landing_is_found_on_the_way(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        // 站在五十格高的石柱顶上:高过场地四周的屏障和邻居的场地,飞起来不被它们挡
        t.fill(4, 1, 10, 6, 50, 14, Blocks.STONE);
        TestBody body = t.body(5, 51, 12);
        body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        Trial.give(body, new ItemStack(Items.FIREWORK_ROCKET, 24));
        BlockPos start = t.at(5, 51, 12);
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
        t.go(body, Goals.column(goal.getX(), goal.getZ()), RouteSpec.defaults()).within(2500)
                .then(r -> {
                    var glides = r.report.ledger().glides();
                    if (glides.size() != 1 || glides.get(0).blocks() < 250 || glides.get(0).airborne()) {
                        throw new GameTestAssertException("该有一段飞了两百多格的滑翔:" + glides);
                    }
                    if (r.body.isFallFlying() || r.body.getAbilities().flying) {
                        throw new GameTestAssertException("收场了还在飞");
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

    /** 烟花只有一支,飞这么远不够(留余量):不飞,全程走路,账上没有滑翔。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 1400)
    public static void too_few_rockets_means_walking(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 1);
        t.go(body, Goals.at(t.at(120, 1, 12)), RouteSpec.defaults()).within(1000)
                .then(r -> {
                    if (!r.report.ledger().glides().isEmpty()) {
                        throw new GameTestAssertException("烟花不够不该飞:" + r.report.ledger().glides());
                    }
                    var why = r.report.ledger().grounded();
                    if (why == null || why.why() != com.dwinovo.numen.pathing.drive.EditLedger.Why.TOO_FEW_ROCKETS || why.have() != 1) {
                        throw new GameTestAssertException("账上该写明没飞是因为烟花不够(有 1 支):" + why);
                    }
                })
                .arrives();
    }

    /** 没穿鞘翅,走一百多格:全程走路,账上写明是因为没穿可用的鞘翅,模型从回执里能读到。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 1400)
    public static void a_long_walk_without_an_elytra_says_why(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(5, 1, 12);
        Trial.give(body, new ItemStack(Items.FIREWORK_ROCKET, 8));
        t.go(body, Goals.at(t.at(120, 1, 12)), RouteSpec.defaults()).within(1000)
                .then(r -> {
                    var why = r.report.ledger().grounded();
                    if (why == null || why.why() != com.dwinovo.numen.pathing.drive.EditLedger.Why.NO_ELYTRA) {
                        throw new GameTestAssertException("账上该写明没飞是因为没穿鞘翅:" + why);
                    }
                })
                .arrives();
    }

    /** 飞着烟花被拿光(身上没了):改降到够得着的平地,不摔;之后走路的段把剩下的走完。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 1800)
    public static void running_out_of_rockets_in_the_air_ends_in_an_emergency_landing(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 8);
        int[] flying = {0};
        t.go(body, Goals.at(t.at(152, 1, 12)), RouteSpec.defaults()).within(1500)
                .during(r -> {
                    if (r.body.isFallFlying() && flying[0]++ == 3) {
                        r.body.getInventory().items.replaceAll(stack -> stack.is(Items.FIREWORK_ROCKET) ? ItemStack.EMPTY : stack);
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
                })
                .arrives();
    }

    /** 没有烟花,站在十五格高的石柱顶上:从边沿走出去展开,只靠高度滑到七十多格外落地,一支烟花也没放,没掉血。脚下没有路下去,只能飞。 */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 1400)
    public static void without_rockets_a_body_glides_off_a_height(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(4, 1, 10, 6, 14, 14, Blocks.STONE);
        TestBody body = t.body(5, 15, 12);
        body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        t.go(body, Goals.at(t.at(120, 1, 12)), RouteSpec.defaults()).within(1000)
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
     * 飞到一半前方多出一堵墙(宽二十格,从地面砌到顶,只在正中留一扇三格宽三格高的门洞供人走):推演否决撞墙,滑翔在墙前落地(或从门洞里过去),
     * 不撞上去(不掉血);落地后走路的段穿过门洞走完。
     */
    @GameTest(template = SKY, batch = BATCH, timeoutTicks = 1800)
    public static void a_wall_that_appears_ahead_is_vetoed_by_the_rehearsal(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = winged(t, 5, 8);
        int[] flying = {0};
        t.go(body, Goals.at(t.at(130, 1, 12)), RouteSpec.defaults()).within(1500)
                .during(r -> {
                    if (r.body.isFallFlying() && flying[0]++ == 40) {
                        int x = (int) (r.body.getX() - t.origin.getX()) + 44;
                        for (int dy = 1; dy <= 38; dy++) {
                            for (int z = 2; z <= 21; z++) {
                                if (dy > 3 || z < 11 || z > 13) {
                                    t.change(x, dy, z, Blocks.STONE.defaultBlockState());
                                }
                            }
                        }
                    }
                })
                .then(r -> {
                    if (r.lowestHealth < 20) {
                        throw new GameTestAssertException("撞上了墙或摔了:最低血量 " + r.lowestHealth);
                    }
                    if (r.report.ledger().glides().size() != 1) {
                        throw new GameTestAssertException("该有一段滑翔:" + r.report.ledger().glides());
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
