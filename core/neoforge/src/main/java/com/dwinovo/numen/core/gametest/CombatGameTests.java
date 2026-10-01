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
        TaskRecord record = command(companion, "fight attack " + slime.getId()).task();

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
     * 目标站在它那一格的远边上:她那一格离它那一格三格整,可她眼睛到它碰撞箱有三格多,手够不着。走位说到了、出手说够不着,
     * 她就站在原地一刀不挥,直到任务超时;走位与出手问的是同一个"够得着",她就会再往前走一格去打。僵尸不动不还手,测的只是
     * 她走不走进够得着的地方。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
    public static void attack_steps_in_on_a_target_at_the_far_edge_of_its_cell(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = armedCompanion(helper, new BlockPos(5, 2, 8));
        Zombie zombie = EntityType.ZOMBIE.create(level);
        helper.assertTrue(zombie != null, "zombie did not spawn");
        BlockPos at = helper.absolutePos(new BlockPos(8, 2, 8));
        zombie.moveTo(at.getX() + 0.95, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        zombie.setNoAi(true);
        level.addFreshEntity(zombie);
        float startHealth = zombie.getHealth();
        ToolRun attack = command(companion, "fight attack " + zombie.getId());

        succeedWhen(helper, () -> {
            helper.assertTrue(attack.task() != null, "the attack was not accepted: " + attack.reply());
            helper.assertTrue(zombie.getHealth() < startHealth && zombie.getLastHurtByMob() == companion,
                    "she stood " + String.format("%.2f", companion.distanceTo(zombie))
                            + " away and never hit the zombie: " + attack.task().getResult());
            zombie.discard();
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 主人在线、却离她很远时,要打的那只站在隔壁区块里:她自己那块加载垫得让伸手所及的邻区块也在跑实体刻,否则那只是冻住的——挨了
     * 第一下之后受击无敌帧永远不退,她一直等着出第二刀,它也打不死。GameTest 的场地区块是钉住的,这里搬到两千格外、高处
     * 一块没人钉的地方现搭一条石台,让她的加载垫成为那里唯一的票据。僵尸不动,测的只是她能不能把它打死。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_combat")
    public static void attack_finishes_a_target_across_a_chunk_border_far_from_players(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 她站在区块 cx 的东边沿上,僵尸站在隔壁区块 cx + 1 的西边沿上
        BlockPos near = helper.absolutePos(BlockPos.ZERO);
        int cx = (near.getX() >> 4) + 128;
        int cz = near.getZ() >> 4;
        int y = 200;
        int z = cz * 16 + 7;
        level.getChunk(cx, cz);
        level.getChunk(cx + 1, cz);
        for (int x = cx * 16 + 10; x <= cx * 16 + 20; x++) {
            level.setBlockAndUpdate(new BlockPos(x, y - 1, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        }
        // 加载垫只在主人在线时续:主人站在场地里(GameTest 没有真玩家,拿另一个同伴的身份顶上,它自己不带区块)
        BlockPos home = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer owner = CompanionFactory.spawn(level.getServer(), java.util.UUID.randomUUID(),
                "gametest_far_owner", java.util.UUID.randomUUID(), level,
                new net.minecraft.world.phys.Vec3(home.getX() + 0.5, home.getY(), home.getZ() + 0.5));
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), java.util.UUID.randomUUID(),
                "gametest_far_fighter", owner.getUUID(), level,
                new net.minecraft.world.phys.Vec3(cx * 16 + 13.5, y, z + 0.5));
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
        Zombie zombie = EntityType.ZOMBIE.create(level);
        helper.assertTrue(zombie != null, "zombie did not spawn");
        ToolRun[] attack = new ToolRun[1];

        // 等她的加载垫把两块区块都立起来,放下僵尸;世界里找得到它了再下命令
        steps(helper)
                .thenIdle(40)
                .thenExecute(() -> {
                    zombie.moveTo(cx * 16 + 16.5, y, z + 0.5, 0.0f, 0.0f);
                    zombie.setNoAi(true);
                    level.addFreshEntity(zombie);
                })
                .thenWaitUntil(() -> helper.assertTrue(level.getEntity(zombie.getId()) == zombie,
                        "the zombie never showed up in the world; she is at " + companion.blockPosition()
                                + " in chunk " + companion.chunkPosition()))
                .thenExecute(() -> attack[0] = command(companion, "fight attack " + zombie.getId()))
                .thenWaitUntil(() -> {
                    helper.assertTrue(attack[0].task() != null, "the attack was not accepted: " + attack[0].reply());
                    helper.assertTrue(zombie.isDeadOrDying() && zombie.getLastHurtByMob() == companion,
                            "the zombie across the chunk border is still up (health " + zombie.getHealth()
                                    + ", hurt time " + zombie.hurtTime + "): " + attack[0].task().getResult());
                })
                .thenExecute(() -> {
                    zombie.discard();
                    CompanionFactory.despawn(level.getServer(), companion);
                    CompanionFactory.despawn(level.getServer(), owner);
                })
                .thenSucceed();
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
        TaskRecord record = command(companion, "fight attack " + pig.getId()).task();

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
        ToolRun nobody = command(companion, "fight attack 999998");
        ToolRun attack = command(companion, "fight attack " + pig.getId() + " 999999");
        String recorded = com.dwinovo.numen.entity.CompanionRegistry.get(server).find(companion.getUUID()).taskArgs();

        succeedWhen(helper, () -> {
            helper.assertTrue(nobody.done() && !nobody.succeeded() && nobody.outcome().contains("999998"),
                    "naming only missing entities did not fail on the spot: " + nobody.reply());
            helper.assertTrue(attack.task() != null, "the attack was not accepted: " + attack.reply());
            helper.assertTrue(recorded.contains("fight attack " + pig.getUUID() + "\"")
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
