package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;
import static com.dwinovo.numen.pathing.gametest.Trial.SKY;

import com.dwinovo.numen.pathing.api.Snapshots;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Kinematics;

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
 * 平地起飞到远处降落、烟花不够就走路、飞着烟花用完迫降;另有穿着鞘翅走路(垫柱上升、跳沟)不误展开。前方撞墙推演否决是局部控制器的事,见单测 PilotTest 与 FlightGameTests。
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
