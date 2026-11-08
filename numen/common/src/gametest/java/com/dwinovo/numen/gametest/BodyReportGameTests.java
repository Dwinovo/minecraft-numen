package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.Constants;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * 身体做的事如实报告:一次动作的回执说明她背包里多了什么少了什么、血量与骑乘的变化、手里的装备用坏了;她没要求而自己发生的
 * (进度奖励进了背包)用事件。都从 Lua 入口调。
 */
public class BodyReportGameTests {

    @BeforeBatch(batch = "numen_body_report")
    public static void prepareBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    private static void sample(String what, ToolRun run) {
        Constants.LOG.info("[numen-sample] {} -> {}", what, run.outcome());
    }

    /** 右键带鞍的猪:她骑了上去,回执说骑上了什么。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_body_report")
    public static void riding_a_saddled_pig_is_reported(GameTestHelper helper) {
        Pig pig = EntityType.PIG.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(10, 2, 8));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);
        pig.equipSaddle(SoundSource.NEUTRAL);
        helper.getLevel().addFreshEntity(pig);
        NumenPlayer companion = spawnAt(helper, "gametest_rider", new BlockPos(8, 2, 8), false);
        ToolRun ride = lua(companion, "numen.use.entity(" + ref(pig) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(ride.done() && ride.succeeded(), "use entity did not finish: " + ride.outcome());
            helper.assertTrue(companion.getVehicle() == pig, "she is not riding the pig");
            helper.assertTrue(ride.outcome().contains("now riding " + com.dwinovo.numen.api.sdk.EntityLabel.of(pig)),
                    "the reply does not say she is riding the pig: " + ride.outcome());
            sample("ride a saddled pig", ride);
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 挖的时候手里的镐碎了:这一挖的结果写明"用坏了"。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_body_report")
    public static void a_pickaxe_breaking_in_a_dig_is_reported(GameTestHelper helper) {
        BlockPos stone = helper.absolutePos(new BlockPos(5, 2, 5));
        helper.getLevel().setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_breaker", new BlockPos(3, 2, 5), false);
        ItemStack pick = new ItemStack(Items.STONE_PICKAXE);
        pick.setDamageValue(pick.getMaxDamage() - 1);
        companion.getInventory().add(pick);
        ToolRun dig = lua(companion, "numen.work.dig(" + xyz(stone) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(dig.done(), "dig has not finished");
            helper.assertTrue(helper.getLevel().getBlockState(stone).isAir(), "the stone was not dug: " + dig.outcome());
            helper.assertTrue(dig.outcome().contains("your minecraft:stone_pickaxe broke while it was in your main hand"),
                    "the reply does not say the pickaxe broke: " + dig.outcome());
            sample("pickaxe breaks in a dig", dig);
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 她达成一个带经验奖励的进度:没有哪次调用要这个结果,所以来一条事件,写明背包与经验实际的变化。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_body_report")
    public static void an_advancement_reward_arrives_as_an_event(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_achiever", new BlockPos(4, 2, 4), false);
        // 这一代带经验奖励的进度取 bullseye(奖励 50 点经验)
        var holder = level.getServer().getAdvancements().get(new ResourceLocation("minecraft:adventure/bullseye"));
        var advancements = companion.getAdvancements();
        List<String> remaining = new java.util.ArrayList<>();
        advancements.getOrStartProgress(holder).getRemainingCriteria().forEach(remaining::add);
        remaining.forEach(criterion -> advancements.award(holder, criterion));

        succeedWhen(helper, () -> {
            List<String> told = GameTestKit.events(companion).stream()
                    .filter(e -> EventTypes.ADVANCEMENT_REWARD.equals(e.type()))
                    .map(com.dwinovo.numen.api.agent.inbox.EventQueue.Entry::text).toList();
            helper.assertTrue(told.size() == 1 && told.get(0).contains("experience +50 points"),
                    "not one advancement_reward event with the experience: " + told);
            Constants.LOG.info("[numen-sample] advancement reward event -> {}", told.get(0));
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
