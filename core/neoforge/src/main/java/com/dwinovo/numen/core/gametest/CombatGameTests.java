package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.combat.Menace;
import com.dwinovo.numen.core.combat.Swing;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskRecord;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
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
        helper.succeedWhen(() -> {
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
            helper.assertTrue(!zombie.isAlive(), "zombie still up");
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
        TaskRecord record = new com.dwinovo.numen.core.tools.CombatOps().attack(
                List.of(pig.getId()), TaskDispatch.ctx("gametest-hunt", companion));
        TaskDispatch.setTask(companion, record, null, reply -> {});

        helper.succeedWhen(() -> {
            helper.assertTrue(!pig.isAlive(), "the pig is still alive — she never walked over to hit it");
            CompanionFactory.despawn(level.getServer(), companion);
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
