package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.drive.DiveLog;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 憋气:封顶的水道一口气游不完就不下水、结局说出那一段水下;短到憋得住的照游;上面敞开能换气的长水道照游;戴海龟壳、带着
 * 水下呼吸效果能游更长的。都断言没掉血、潜过的水照实记在账上。
 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public class BreathGameTests {

    private static final String BATCH = "pathing_breath";
    /** 水道所在的那一行。 */
    private static final int Z = 20;

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /**
     * 地板下面一条水道:沿 x 从 {@code a} 到 {@code b},水两格高(脚在 y = -2),{@code roofed} 时顶就是地板那一层基岩,只在两头
     * 各开一口竖井通到地面;否则地板那一层也是水,整条水道上面敞开。四壁与底都是基岩。一道基岩墙在两头之间横贯整个场地、
     * 高过能跳上去的高度,地面上过不去:两边只有这条水道连着。敞开的水道两岸砌两格高的基岩(不从半路爬上岸),在墙下留两格空气,
     * 浮在水面上就游得过墙,头不必没进水里。
     */
    private static void channel(Trial t, int a, int b, boolean roofed) {
        int wall = (a + b) / 2;
        t.fill(wall, 1, 0, wall, 21, 39, Blocks.BEDROCK);
        if (!roofed) {
            t.fill(a, 1, Z - 1, b, 2, Z - 1, Blocks.BEDROCK);
            t.fill(a, 1, Z + 1, b, 2, Z + 1, Blocks.BEDROCK);
            t.fill(wall, 1, Z, wall, 2, Z, Blocks.AIR);
        }
        t.fill(a - 1, -3, Z - 1, b + 1, 0, Z + 1, Blocks.BEDROCK);
        t.fill(a, -2, Z, b, roofed ? -1 : 0, Z, Blocks.WATER);
        t.set(a, 0, Z, Blocks.WATER);
        t.set(b, 0, Z, Blocks.WATER);
    }

    /** 收场时:身体没掉过血;游过了封顶的水道,潜过的水记在账上,每一段都憋得住(氧气没见底)。 */
    private static void heldEveryBreath(Trial.Run r) {
        if (r.report.dives().isEmpty()) {
            throw new GameTestAssertException("游过了封顶的水道,账上却没有潜过的水");
        }
        neverOutOfAir(r);
    }

    /** 收场时:身体没掉过血,账上潜过的每一段水都憋得住(氧气没见底)。 */
    private static void neverOutOfAir(Trial.Run r) {
        UpDownGameTests.unhurt(r);
        for (DiveLog.Dive dive : r.report.dives()) {
            if (dive.lowestAir() <= 0) {
                throw new GameTestAssertException("憋到氧气见底:" + dive);
            }
        }
    }

    /**
     * 两口竖井之间 28 格长的封顶水道,一口气要游约 290 刻,原版满氧气安全地只憋得住 240 刻:不下水,结局是憋不住气,点出从哪一口
     * 竖井下去、到哪一口上来;一点血不掉、身体没进过水。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void will_not_dive_a_sealed_channel_longer_than_a_breath(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        channel(t, 5, 33, true);
        TestBody body = t.body(2, 1, Z);
        BlockPos entry = t.at(5, 0, Z);
        BlockPos exit = t.at(33, 0, Z);
        t.go(body, Goals.at(t.at(36, 1, Z)), RouteSpec.defaults()).within(500)
                .during(r -> {
                    if (r.body.isInWater()) {
                        throw new GameTestAssertException("下了水 " + t.rel(r.body.blockPosition()));
                    }
                })
                .fails(Outcome.Breathless.class, o -> {
                    if (o.from().distManhattan(entry) > 2 || o.to().distManhattan(exit) > 2) {
                        throw new GameTestAssertException("那一段水下不对:" + t.rel(o.from()) + " 到 " + t.rel(o.to()));
                    }
                    if (o.held() <= o.spare()) {
                        throw new GameTestAssertException("要憋的比能憋的短,却说憋不住:" + o);
                    }
                })
                .then(UpDownGameTests::unhurt);
    }

    /** 同样的封顶水道只有 8 格长(一口气约 110 刻):游过去,一口气到头,账上一段水下,没掉血。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 900)
    public static void swims_a_sealed_channel_short_enough_to_hold(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        channel(t, 10, 18, true);
        TestBody body = t.body(7, 1, Z);
        t.go(body, Goals.at(t.at(21, 1, Z)), RouteSpec.defaults()).within(800).arrives()
                .then(BreathGameTests::heldEveryBreath);
    }

    /** 28 格长、上面敞开的水道:浮在水面上换着气游过去,没掉血。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 1500)
    public static void swims_a_long_channel_open_above(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        channel(t, 5, 33, false);
        TestBody body = t.body(2, 1, Z);
        t.go(body, Goals.at(t.at(36, 1, Z)), RouteSpec.defaults()).within(1400).arrives()
                .then(BreathGameTests::neverOutOfAir);
    }

    /** 戴着海龟壳(每次下水先有 10 秒不扣氧):28 格长的封顶水道一口气游得完,游过去,没掉血。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 1200)
    public static void a_turtle_shell_holds_long_enough_for_the_long_sealed_channel(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        channel(t, 5, 33, true);
        TestBody body = t.body(2, 1, Z);
        body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.TURTLE_HELMET));
        t.go(body, Goals.at(t.at(36, 1, Z)), RouteSpec.defaults()).within(1100).arrives()
                .then(BreathGameTests::heldEveryBreath);
    }

    /** 带着一分钟的水下呼吸效果:28 格长的封顶水道游过去,没掉血。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 1200)
    public static void water_breathing_holds_long_enough_for_the_long_sealed_channel(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        channel(t, 5, 33, true);
        TestBody body = t.body(2, 1, Z);
        body.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 1200));
        t.go(body, Goals.at(t.at(36, 1, Z)), RouteSpec.defaults()).within(1100).arrives()
                .then(BreathGameTests::heldEveryBreath);
    }
}
