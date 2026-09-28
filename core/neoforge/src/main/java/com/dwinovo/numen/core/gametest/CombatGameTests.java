package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.combat.Menace;
import com.dwinovo.numen.core.combat.Swing;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 战斗:走位带与点名攻击({@code attack})。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class CombatGameTests {

    /** 战斗批次前置:普通难度(和平会把僵尸当场收走)+ 半夜(白天僵尸会被晒死)。 */
    @BeforeBatch(batch = "numen_combat")
    public static void prepareCombatBatch(ServerLevel level) {
        settleWorld(level, Difficulty.NORMAL, MIDNIGHT);
    }

    /**
     * 走位带:她该稳在「它够不着我」与「我够得着它」之间,而且真的能砍到。
     *
     * <p>盯的是两个反复出错的地方:外沿一旦超过她的够到距离,她会停在打不到的位置站着挨打
     * (实测在 4.0~4.8 之间摆、有效血量 8 掉到 5);内沿一旦叠上格量化补偿,带宽从 1.28 压到
     * 0.57,格分辨率装不下,寻路一路失败,她停在边缘不动。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_combat")
    public static void combat_holds_the_skirmish_band(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = armedCompanion(helper, new BlockPos(3, 2, 3));
        Zombie zombie = spawnZombie(helper, new BlockPos(11, 2, 11), companion);

        double inner = Menace.rawDangerRadius(zombie, companion);
        double outer = Swing.reachTo(
                companion.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE),
                zombie.getBbWidth());
        float startHealth = zombie.getHealth();

        int[] insideBand = {0};
        succeedWhen(helper, () -> {
            helper.assertTrue(companion.isAlive(), "companion died to a single zombie");
            double d = companion.distanceTo(zombie);
            if (d >= inner && d <= outer) {
                insideBand[0]++;
            }
            // 砍掉血就说明外沿确实在够得着的范围内 —— 站在打不到的地方是这条最先抓的病。
            helper.assertTrue(zombie.getHealth() < startHealth || !zombie.isAlive()
                            || insideBand[0] < 200,
                    "companion never landed a hit: distance " + String.format("%.2f", d)
                            + " band [" + String.format("%.2f", inner) + ", "
                            + String.format("%.2f", outer) + "]");
            // 倒下、而且最后伤它的是她:被收走或被别的东西弄死的僵尸也"不在了",那不算她打赢
            helper.assertTrue(zombie.isDeadOrDying() && zombie.getLastHurtByMob() == companion,
                    "zombie still up, or it did not fall to her");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 够得比她还远的怪(原版里是十四号往上的史莱姆:它的击打范围随身宽按对角放大,她的够到距离只加半个身宽):打得着
     * 又挨不着的那条带不存在,走位环的内沿归零,她照样走进够得着的地方砍它,而不是在编走位目标时出错收场。史莱姆不动
     * 不还手,测的只是她走不走过去打。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_combat")
    public static void attack_walks_in_on_a_creature_that_outreaches_her(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = armedCompanion(helper, new BlockPos(2, 2, 2));
        var slime = EntityType.SLIME.create(level);
        helper.assertTrue(slime != null, "slime did not spawn");
        slime.setSize(14, true);
        BlockPos at = helper.absolutePos(new BlockPos(11, 2, 11));
        slime.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        slime.setNoAi(true);
        level.addFreshEntity(slime);
        helper.assertTrue(Menace.rawDangerRadius(slime, companion) >= Swing.reachTo(
                        companion.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE), slime.getBbWidth()),
                "this slime does not outreach her, the scene tests nothing");
        float startHealth = slime.getHealth();
        TaskRecord record = command(companion, "fight attack --entity_ids " + slime.getId()).task();

        succeedWhen(helper, () -> {
            helper.assertTrue(record.getResult() == null || !record.getResult().message().contains("internal error"),
                    "the attack broke down: " + record.getResult());
            helper.assertTrue(slime.getHealth() < startHealth && slime.getLastHurtByMob() == companion,
                    "she never walked in to hit the slime");
            slime.discard();
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 点名打不敌对的东西:一头猪,附近一只怪都没有。她必须走过去把它打掉——走位目标由
     * "有没有目标"决定,不由"附近有没有怪"决定;后者只是躲避场。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_combat")
    public static void attack_hunts_a_named_passive_target(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = armedCompanion(helper, new BlockPos(2, 2, 2));
        var pig = EntityType.PIG.create(level);
        helper.assertTrue(pig != null, "pig did not spawn");
        BlockPos at = helper.absolutePos(new BlockPos(13, 2, 13));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);   // 站着别跑,这条测的是她走不走过去,不是追逐
        level.addFreshEntity(pig);
        TaskRecord record = command(companion, "fight attack --entity_ids " + pig.getId()).task();

        succeedWhen(helper, () -> {
            helper.assertTrue(pig.isDeadOrDying() && pig.getLastHurtByMob() == companion,
                    "the pig is still alive — she never walked over to hit it");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 点名攻击落盘重放的那一行写的是目标的 UUID:运行期编号重启后会发给别的东西,照旧号重放可能打到毫不相干的一只。
     * 受理时找不到的编号不写进去(任务照旧记它丢失);一只都找不到就当场失败,不留一行会变成不点名清场的重放。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
    public static void attack_is_replayed_by_uuid_not_by_runtime_id(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var pig = EntityType.PIG.create(level);
        helper.assertTrue(pig != null, "pig did not spawn");
        BlockPos at = helper.absolutePos(new BlockPos(13, 2, 13));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);
        level.addFreshEntity(pig);
        BlockPos spawn = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer companion = com.dwinovo.numen.entity.Companions.summon(server, java.util.UUID.randomUUID(),
                "gametest_uuid_hunter", level, new net.minecraft.world.phys.Vec3(spawn.getX() + 0.5, spawn.getY(),
                        spawn.getZ() + 0.5));
        ToolRun nobody = command(companion, "fight attack --entity_ids 999998");
        ToolRun attack = command(companion, "fight attack --entity_ids " + pig.getId() + " 999999");
        String recorded = com.dwinovo.numen.entity.CompanionRegistry.get(server).find(companion.getUUID()).taskArgs();

        succeedWhen(helper, () -> {
            helper.assertTrue(nobody.done() && !nobody.succeeded() && nobody.outcome().contains("999998"),
                    "naming only missing entities did not fail on the spot: " + nobody.reply());
            helper.assertTrue(attack.task() != null, "the attack was not accepted: " + attack.reply());
            helper.assertTrue(recorded.contains("--entity_ids " + pig.getUUID() + "\"")
                            && !recorded.contains("999999"),
                    "the replay recipe does not name exactly the pig by its UUID: " + recorded);
            com.dwinovo.numen.entity.Companions.dismiss(server, companion);
            pig.discard();
        });
    }

    private static Zombie spawnZombie(GameTestHelper helper, BlockPos rel, NumenPlayer target) {
        ServerLevel level = helper.getLevel();
        Zombie zombie = EntityType.ZOMBIE.create(level);
        helper.assertTrue(zombie != null, "zombie did not spawn");
        BlockPos at = helper.absolutePos(rel);
        zombie.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        zombie.setTarget(target);
        level.addFreshEntity(zombie);
        return zombie;
    }
}
