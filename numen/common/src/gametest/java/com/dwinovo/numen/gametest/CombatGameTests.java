package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.Bodies;
import com.dwinovo.numen.combat.Menace;
import com.dwinovo.numen.combat.Swing;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.TaskRecord;
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
import net.minecraft.world.phys.Vec3;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/** 战斗:走位带与点名攻击({@code attack});她四周的怪:附近栏、被锁定、没了。 */
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
    @GameTest(template = "floor16", timeoutTicks = 405, batch = "numen_combat")
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
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
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
        TaskRecord<?> record = lua(companion, "numen.fight.attack(" + ref(slime) + ")").task();

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
        ToolRun attack = lua(companion, "numen.fight.attack(" + ref(zombie) + ")");

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
     * 要打的那只一刻不停地在原地晃(真的怪围着主人打时就是这样):走位的目标跟着它每刻都变,从她脚下派出去的那次搜索得搜完,
     * 不能每变一次就作废重派——那样一次也搜不完,她站在远处一步不动。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
    public static void attack_closes_in_on_a_target_that_never_stands_still(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = armedCompanion(helper, new BlockPos(2, 2, 8));
        Zombie zombie = EntityType.ZOMBIE.create(level);
        helper.assertTrue(zombie != null, "zombie did not spawn");
        BlockPos at = helper.absolutePos(new BlockPos(12, 2, 8));
        zombie.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        zombie.setNoAi(true);
        level.addFreshEntity(zombie);
        float startHealth = zombie.getHealth();
        // 每刻在它那一格里挪一点,不出格
        helper.onEachTick(() -> zombie.setPos(at.getX() + 0.5 + 0.2 * Math.sin(level.getGameTime() * 0.7),
                zombie.getY(), at.getZ() + 0.5 + 0.2 * Math.cos(level.getGameTime() * 0.7)));
        ToolRun attack = lua(companion, "numen.fight.attack(" + ref(zombie) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(attack.task() != null, "the attack was not accepted: " + attack.reply());
            helper.assertTrue(zombie.getHealth() < startHealth && zombie.getLastHurtByMob() == companion,
                    "she is still " + String.format("%.2f", companion.distanceTo(zombie))
                            + " away and never hit the jittering zombie: " + attack.task().getResult());
            zombie.discard();
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 离真玩家很远时,要打的那只站在隔壁区块里:邻区块得和她脚下这块一样在跑实体刻,否则那只是冻住的——挨了
     * 第一下之后受击无敌帧永远不退,她一直等着出第二刀,它也打不死。GameTest 的场地区块是钉住的,这里搬到两千格外、高处
     * 一块没人钉的地方现搭一条石台,让她作为玩家带起来的区块成为那里唯一的票据。僵尸不动,测的只是她能不能把它打死。
     */
    @GameTest(template = "floor16", timeoutTicks = 480, batch = "numen_combat")
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
        NumenPlayer companion = Bodies.spawn(level.getServer(), java.util.UUID.randomUUID(),
                "gametest_far_fighter", java.util.UUID.randomUUID(), level,
                new net.minecraft.world.phys.Vec3(cx * 16 + 13.5, y, z + 0.5));
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
        Zombie zombie = EntityType.ZOMBIE.create(level);
        helper.assertTrue(zombie != null, "zombie did not spawn");
        ToolRun[] attack = new ToolRun[1];

        // 等她带起来的区块把两块都立起来、跑上实体刻,放下僵尸;世界里找得到它了再下命令
        steps(helper)
                .thenWaitUntil(() -> {
                    helper.assertTrue(level.isPositionEntityTicking(companion.blockPosition()),
                            "her own chunk is not entity-ticking yet");
                    helper.assertTrue(level.isPositionEntityTicking(new BlockPos(cx * 16 + 16, y, z)),
                            "the chunk across the border is not entity-ticking yet");
                })
                .thenExecute(() -> {
                    zombie.moveTo(cx * 16 + 16.5, y, z + 0.5, 0.0f, 0.0f);
                    zombie.setNoAi(true);
                    level.addFreshEntity(zombie);
                })
                .thenWaitUntil(() -> helper.assertTrue(level.getEntity(zombie.getId()) == zombie,
                        "the zombie never showed up in the world; she is at " + companion.blockPosition()
                                + " in chunk " + companion.chunkPosition()))
                .thenExecute(() -> attack[0] = lua(companion, "numen.fight.attack(" + ref(zombie) + ")"))
                .thenWaitUntil(() -> {
                    helper.assertTrue(attack[0].task() != null, "the attack was not accepted: " + attack[0].reply());
                    helper.assertTrue(zombie.isDeadOrDying() && zombie.getLastHurtByMob() == companion,
                            "the zombie across the chunk border is still up (health " + zombie.getHealth()
                                    + ", hurt time " + zombie.hurtTime + "): " + attack[0].task().getResult());
                })
                .thenExecute(() -> {
                    zombie.discard();
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 点名打不敌对的东西:一头猪,附近一只怪都没有。她必须走过去把它打掉——走位目标由
     * "有没有目标"决定,不由"附近有没有怪"决定;后者只是躲避场。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
    public static void attack_hunts_a_named_passive_target(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = armedCompanion(helper, new BlockPos(2, 2, 2));
        var pig = EntityType.PIG.create(level);
        helper.assertTrue(pig != null, "pig did not spawn");
        BlockPos at = helper.absolutePos(new BlockPos(13, 2, 13));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);   // 站着别跑,这条测的是她走不走过去,不是追逐
        level.addFreshEntity(pig);
        TaskRecord<?> record = lua(companion, "numen.fight.attack(" + ref(pig) + ")").task();

        succeedWhen(helper, () -> {
            helper.assertTrue(pig.isDeadOrDying() && pig.getLastHurtByMob() == companion,
                    "the pig is still alive — she never walked over to hit it");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 点名攻击落盘重放的那一行写的是目标的身份(种类@UUID 前六位):重启后认的还是同一只。
     * 受理时找不到的身份不写进去;一只都找不到就当场失败,不留一行会变成不点名清场的重放。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
    public static void attack_is_replayed_by_the_targets_id(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var pig = EntityType.PIG.create(level);
        helper.assertTrue(pig != null, "pig did not spawn");
        BlockPos at = helper.absolutePos(new BlockPos(13, 2, 13));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);
        level.addFreshEntity(pig);
        BlockPos spawn = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer companion = com.dwinovo.numen.api.gametest.Bodies.summon(server, java.util.UUID.randomUUID(),
                "gametest_uuid_hunter", level, new net.minecraft.world.phys.Vec3(spawn.getX() + 0.5, spawn.getY(),
                        spawn.getZ() + 0.5));
        ToolRun nobody = lua(companion, "numen.fight.attack(\"pig@999998\")");
        ToolRun attack = lua(companion, "numen.fight.attack(" + ref(pig) + ")");
        String recorded = com.dwinovo.numen.api.entity.CompanionRegistry.get(server).find(companion.getUUID()).taskLua();

        succeedWhen(helper, () -> {
            helper.assertTrue(nobody.done() && !nobody.succeeded() && nobody.outcome().contains("999998"),
                    "naming only missing entities did not fail on the spot: " + nobody.reply());
            helper.assertTrue(attack.task() != null, "the attack was not accepted: " + attack.reply());
            helper.assertTrue(recorded.equals("numen.fight.attack(" + ref(pig) + ")"),
                    "the replay recipe does not name exactly the pig by its id: " + recorded);
            com.dwinovo.numen.api.entity.Companions.dismiss(server, companion);
            pig.discard();
        });
    }

    /** 一只已经不在世界里的生物的身份交给 numen.fight.attack:当场拒绝,说它不在附近,不受理。 */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_combat")
    public static void a_gone_mobs_id_is_refused_on_the_spot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var pig = EntityType.PIG.create(level);
        helper.assertTrue(pig != null, "pig did not spawn");
        BlockPos at = helper.absolutePos(new BlockPos(13, 2, 13));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        level.addFreshEntity(pig);
        String id = ref(pig);
        pig.discard();
        NumenPlayer companion = spawnAt(helper, "gametest_gone_id", new BlockPos(3, 2, 3), false);
        ToolRun attack = lua(companion, "numen.fight.attack(" + id + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(attack.done() && !attack.succeeded() && attack.task() == null
                            && attack.outcome().contains(id.replace("\"", "") + " is not near you"),
                    "a dead mob's id was not refused on the spot: " + attack.reply());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 两只同码的生物(UUID 前六位一样):点名它们的短码被拒绝,回执并列出是哪几只,不猜其中一只。 */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_combat")
    public static void two_mobs_with_the_same_code_are_refused_naming_both(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var first = EntityType.PIG.create(level);
        var second = EntityType.COW.create(level);
        helper.assertTrue(first != null && second != null, "the mobs did not spawn");
        second.setUUID(new java.util.UUID(first.getUUID().getMostSignificantBits() ^ 1L,
                first.getUUID().getLeastSignificantBits()));
        BlockPos at = helper.absolutePos(new BlockPos(13, 2, 13));
        first.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        second.moveTo(at.getX() - 2.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        level.addFreshEntity(first);
        level.addFreshEntity(second);
        NumenPlayer companion = spawnAt(helper, "gametest_same_code", new BlockPos(3, 2, 3), false);
        ToolRun attack = lua(companion, "numen.fight.attack(" + ref(first) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(attack.done() && !attack.succeeded() && attack.task() == null
                            && attack.outcome().contains("names 2 entities")
                            && attack.outcome().contains(com.dwinovo.numen.api.sdk.EntityLabel.of(first))
                            && attack.outcome().contains(com.dwinovo.numen.api.sdk.EntityLabel.of(second)),
                    "a shared code was not refused with both candidates: " + attack.reply());
            first.discard();
            second.discard();
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 库里的 {@code numen.fight.clear}:四周两只僵尸,一次 {@code numen.fight.attack} 打一只,打到范围里一只不剩,返回打了几场。两只都倒在
     * 她手下,一场一个 numen.fight.attack。半径给小,不扫到隔壁场地。
     */
    @GameTest(template = "floor16", timeoutTicks = 315, batch = "numen_combat")
    public static void fight_clear_fights_every_hostile_around_her_one_at_a_time(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = armedCompanion(helper, new BlockPos(8, 2, 8));
        List<Zombie> zombies = List.of(still(helper, new BlockPos(4, 2, 8)), still(helper, new BlockPos(12, 2, 9)));
        ToolRun clear = lua(companion, "return numen.fight.clear(7)");

        succeedWhen(helper, () -> {
            helper.assertTrue(clear.receipt() != null, "numen.fight.clear has not finished");
            helper.assertTrue(clear.ranToTheEnd() && clear.receipt().contains("\\nreturned: 2"),
                    "numen.fight.clear did not end after two fights: " + clear.receipt());
            helper.assertTrue(clear.tasks("numen.fight.attack").size() == 2,
                    "not one numen.fight.attack per zombie: " + clear.receipt());
            for (Zombie zombie : zombies) {
                helper.assertTrue(zombie.isDeadOrDying() && zombie.getLastHurtByMob() == companion,
                        "a zombie is still up, or it did not fall to her");
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 打不过就跑归逃跑本能,跑不掉就就地还手:她只剩四颗心、手里有剑,一只僵尸追着她,脚下是悬在半空的一块 5×5 石台——四下
     * 三十二格内没有能站的落点。逃跑本能先接过身体,找不到落点就让出来,留下一条说"没有退路"的本能事件;自卫接着打,僵尸挨了她的剑。
     * 石台搬到两千格外、高处一块没人钉的地方,和隔壁场地不沾边,所以四下确实无处可去;她周围的区块由她这个玩家自己带起来,等她脚下进了实体刻再开始。本能事件进她的收件箱,读得到。
     */
    @GameTest(template = "floor16", timeoutTicks = 396, batch = "numen_combat")
    public static void an_outmatched_body_with_no_way_out_fights_back(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos near = helper.absolutePos(BlockPos.ZERO);
        int cx = (near.getX() >> 4) + 160;
        int cz = near.getZ() >> 4;
        int y = 200;
        int x0 = cx * 16 + 6;
        int z0 = cz * 16 + 6;
        for (int x = x0; x < x0 + 5; x++) {
            for (int z = z0; z < z0 + 5; z++) {
                level.setBlockAndUpdate(new BlockPos(x, y - 1, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            }
        }
        NumenPlayer companion = GameTestKit.withBrain(Bodies.spawn(level.getServer(),
                java.util.UUID.randomUUID(), "gametest_cornered", java.util.UUID.randomUUID(), level,
                new net.minecraft.world.phys.Vec3(x0 + 3.5, y, z0 + 2.5)));
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
        companion.getFoodData().setFoodLevel(20);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        helper.assertTrue(zombie != null, "zombie did not spawn");

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(companion.blockPosition()),
                        "the chunk she stands in is not entity-ticking yet"))
                .thenIdle(40)
                .thenExecute(() -> {
                    // 进世界时血是满的:站稳了再压到四颗心,僵尸这才来
                    companion.setHealth(8.0f);
                    zombie.moveTo(x0 + 0.5, y, z0 + 2.5, 0.0f, 0.0f);
                    zombie.setTarget(companion);
                    level.addFreshEntity(zombie);
                })
                .thenWaitUntil(() -> {
                    var flee = GameTestKit.events(companion).stream()
                            .filter(e -> e.type().equals(com.dwinovo.numen.api.agent.inbox.EventTypes.REFLEX)
                                    && e.text().contains("reflex=\"flee\""))
                            .toList();
                    helper.assertTrue(!flee.isEmpty() && flee.get(0).text().contains("no way out"),
                            "the flee instinct did not report that there was nowhere to run: "
                                    + GameTestKit.events(companion));
                    helper.assertTrue(zombie.getLastHurtByMob() == companion,
                            "cornered, she did not fight back (zombie health " + zombie.getHealth() + ")");
                })
                .thenExecute(() -> {
                    zombie.discard();
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /** 原版玩家刚进世界的 60 刻里不受伤害,打她要等这段过去。 */
    private static final long SPAWN_PROTECTION_TICKS = 70;

    /**
     * 玩家打她一下,她被击退。原版玩家攻击后发完速度包就把服务端速度清零,击退本该由目标的客户端应用;她没有客户端,
     * 靠 {@code FakeClient} 接住这个包。攻击者站在她西边,她该被推向东。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
    public static void a_players_hit_knocks_her_back(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnBare(helper, "knocked_by_player", new BlockPos(8, 2, 8), false);
        var attacker = presentPlayer(helper, null, "gametest_puncher");
        Vec3 start = companion.position();
        attacker.teleportTo(level, start.x - 1.0, start.y, start.z, 270.0f, 0.0f);
        // 测试服务器默认不许玩家互相伤害(专用服务器读 server.properties 的 pvp)
        boolean pvp = level.getServer().isPvpAllowed();
        level.getServer().setPvpAllowed(true);
        boolean[] hit = {false};
        long ready = level.getGameTime() + SPAWN_PROTECTION_TICKS;
        succeedWhen(helper, () -> {
            helper.assertTrue(level.getGameTime() >= ready, "she is still under spawn protection");
            if (!hit[0]) {
                hit[0] = true;
                attacker.attack(companion);
            }
            helper.assertTrue(companion.getX() - start.x > 0.3,
                    "the hit did not move her: dx=" + (companion.getX() - start.x));
            level.getServer().setPvpAllowed(pvp);
            leave(attacker);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 怪打她,击退照旧(服务端上的速度本来就是对的,不经 {@code FakeClient})。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
    public static void a_zombies_hit_knocks_her_back(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnBare(helper, "knocked_by_zombie", new BlockPos(8, 2, 8), false);
        Zombie zombie = still(helper, new BlockPos(7, 2, 8));
        Vec3 start = companion.position();
        boolean[] hit = {false};
        long ready = level.getGameTime() + SPAWN_PROTECTION_TICKS;
        succeedWhen(helper, () -> {
            helper.assertTrue(level.getGameTime() >= ready, "she is still under spawn protection");
            if (!hit[0]) {
                hit[0] = true;
                zombie.doHurtTarget(companion);
            }
            helper.assertTrue(companion.getX() - start.x > 0.3,
                    "the hit did not move her: dx=" + (companion.getX() - start.x));
            zombie.discard();
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 她此刻请求里的 {@code <nearby>} 一栏;半径里什么都没有是空串。 */
    private static String nearbyOf(NumenPlayer companion) {
        String state = com.dwinovo.numen.api.brain.CompanionLoops.get(companion.getUUID()).orElseThrow().runtimeState();
        int from = state.indexOf("<nearby");
        return from < 0 ? "" : state.substring(from, state.indexOf("</nearby>") + "</nearby>".length());
    }

    /**
     * 附近栏逐个列出和她有关系的(盯着她的、她名下的)、敌对的,先后是有关系的在前、敌对的在后;锁定了她的注明;其余按种类计数,
     * 不逐个列。请求里每次现取,她四周什么样它就什么样。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_combat")
    public static void nearby_lists_what_matters_and_counts_the_rest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_nearby_list", new BlockPos(8, 2, 8), false);
        Zombie hunter = still(helper, new BlockPos(11, 2, 8));
        hunter.setTarget(companion);
        Zombie idle = still(helper, new BlockPos(8, 2, 13));
        var wolf = EntityType.WOLF.create(level);
        BlockPos wolfAt = helper.absolutePos(new BlockPos(6, 2, 8));
        wolf.moveTo(wolfAt.getX() + 0.5, wolfAt.getY(), wolfAt.getZ() + 0.5, 0.0f, 0.0f);
        wolf.setNoAi(true);
        wolf.tame(companion);
        level.addFreshEntity(wolf);
        for (int i = 0; i < 5; i++) {
            var cow = EntityType.COW.create(level);
            BlockPos at = helper.absolutePos(new BlockPos(2 + i, 2, 2));
            cow.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
            cow.setNoAi(true);
            level.addFreshEntity(cow);
        }
        for (int i = 0; i < 2; i++) {
            BlockPos at = helper.absolutePos(new BlockPos(12, 2, 3 + i));
            level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, at.getX() + 0.5, at.getY(),
                    at.getZ() + 0.5, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK)));
        }

        succeedWhen(helper, () -> {
            String nearby = nearbyOf(companion);
            String wolfId = com.dwinovo.numen.api.sdk.EntityLabel.of(wolf);
            String hunterId = com.dwinovo.numen.api.sdk.EntityLabel.of(hunter);
            String idleId = com.dwinovo.numen.api.sdk.EntityLabel.of(idle);
            helper.assertTrue(nearby.startsWith("<nearby radius=\"16\">"), "no nearby block: " + nearby);
            helper.assertTrue(nearby.contains(hunterId + " 20/20 HP, 3 blocks east, targeting you"),
                    "the mob after her is not listed as such: " + nearby);
            String wolfLine = nearby.lines().filter(line -> line.startsWith(wolfId)).findFirst().orElse("");
            helper.assertTrue(wolfLine.endsWith(" HP, 2 blocks west"), "her wolf is not listed plain: " + nearby);
            helper.assertTrue(nearby.contains(idleId + " 20/20 HP, 5 blocks south\n"),
                    "the idle hostile is not listed plain: " + nearby);
            helper.assertTrue(nearby.indexOf(wolfId) < nearby.indexOf(hunterId) && nearby.indexOf(hunterId) < nearby.indexOf(idleId),
                    "related first, then hostile, each nearest first: " + nearby);
            helper.assertTrue(nearby.contains("cow×5") && nearby.contains("item×2") && !nearby.contains("cow@"),
                    "the rest is not counted by kind: " + nearby);
            hunter.discard();
            idle.discard();
            wolf.discard();
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 逐个列最多十二条,多出来的并进计数。 */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_combat")
    public static void nearby_lists_twelve_at_most_and_counts_the_overflow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_nearby_cap", new BlockPos(8, 2, 8), false);
        List<Zombie> crowd = new java.util.ArrayList<>();
        for (int i = 0; i < 14; i++) {
            crowd.add(still(helper, new BlockPos(1 + i, 2, 1 + (i % 2) * 13)));
        }

        succeedWhen(helper, () -> {
            String nearby = nearbyOf(companion);
            long listed = nearby.lines().filter(line -> line.startsWith("zombie@")).count();
            helper.assertTrue(listed == 12 && nearby.contains("zombie×2"),
                    "twelve are listed and two counted: " + nearby);
            crowd.forEach(net.minecraft.world.entity.Entity::discard);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 半径里什么都没有:请求里没有这一栏。 */
    @GameTest(template = "floor16", timeoutTicks = 60, batch = "numen_combat")
    public static void nearby_is_left_out_when_nothing_is_around(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_nearby_empty", new BlockPos(8, 2, 8), false);

        succeedWhen(helper, () -> {
            helper.assertTrue(nearbyOf(companion).isEmpty(), "an empty surrounding wrote a nearby block: " + nearbyOf(companion));
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 一只不动不还手的僵尸。 */
    private static Zombie still(GameTestHelper helper, BlockPos rel) {
        Zombie zombie = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(zombie != null, "zombie did not spawn");
        BlockPos at = helper.absolutePos(rel);
        zombie.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        zombie.setNoAi(true);
        helper.getLevel().addFreshEntity(zombie);
        return zombie;
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

    /** 她收件箱里此刻某种类型的事件。 */
    private static List<com.dwinovo.numen.api.agent.inbox.EventQueue.Entry> eventsOf(NumenPlayer companion, String type) {
        return events(companion).stream().filter(e -> e.type().equals(type)).toList();
    }

    /**
     * 一只怪的攻击目标变成她:发一次急件,带短码、种类、距离、方位;它一直盯着不再发,放弃又盯上的抖动在去抖窗口里也不再发。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
    public static void a_mob_that_targets_her_is_announced_once(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_targeted", new BlockPos(8, 2, 8), false);
        Zombie zombie = still(helper, new BlockPos(11, 2, 8));
        String id = com.dwinovo.numen.api.sdk.EntityLabel.of(zombie);

        steps(helper)
                .thenIdle(5)
                .thenExecute(() -> helper.assertTrue(eventsOf(companion, com.dwinovo.numen.api.agent.inbox.EventTypes.TARGETED)
                        .isEmpty(), "announced before anything targeted her"))
                .thenExecute(() -> zombie.setTarget(companion))
                .thenIdle(10)
                .thenExecute(() -> {
                    var told = eventsOf(companion, com.dwinovo.numen.api.agent.inbox.EventTypes.TARGETED);
                    helper.assertTrue(told.size() == 1 && told.get(0).urgent()
                                    && told.get(0).text().contains("mob=\"" + id + "\"")
                                    && told.get(0).text().contains("distance=\"3\"")
                                    && told.get(0).text().contains("bearing=\"east\"")
                                    && told.get(0).text().contains(id + " is now targeting you"),
                            "no urgent targeted event naming it: " + told);
                    zombie.setTarget(null);
                })
                .thenIdle(5)
                .thenExecute(() -> zombie.setTarget(companion))
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertTrue(eventsOf(companion, com.dwinovo.numen.api.agent.inbox.EventTypes.TARGETED).size() == 1,
                            "it was announced again for the jitter: " + events(companion));
                    zombie.discard();
                    CompanionFactory.despawn(helper.getLevel().getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 打她的怪倒下:关注集里的对象被移出世界,发一次不急的 gone,附原因(死了)和打倒它的是谁;她自己打倒的写 you。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
    public static void a_mob_in_her_focus_that_dies_is_reported_gone_with_who_killed_it(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_gone_killed", new BlockPos(8, 2, 8), false);
        Zombie zombie = still(helper, new BlockPos(11, 2, 8));
        zombie.setTarget(companion);
        String id = com.dwinovo.numen.api.sdk.EntityLabel.of(zombie);

        steps(helper)
                .thenIdle(5)
                .thenExecute(() -> zombie.hurt(companion.damageSources().playerAttack(companion), 100.0f))
                .thenIdle(40)
                .thenExecute(() -> {
                    var gone = eventsOf(companion, com.dwinovo.numen.api.agent.inbox.EventTypes.GONE);
                    helper.assertTrue(gone.size() == 1 && !gone.get(0).urgent()
                                    && gone.get(0).text().contains("mob=\"" + id + "\"")
                                    && gone.get(0).text().contains("reason=\"killed\"")
                                    && gone.get(0).text().contains("by=\"you\""),
                            "no gone event with the cause and the killer: " + gone);
                    CompanionFactory.despawn(helper.getLevel().getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 走出半径不发 gone:关注集里的怪走远了(还在世界里)什么都不说;它之后被移出世界(消失)才发一次,原因写消失。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
    public static void leaving_the_radius_is_not_gone_but_vanishing_is(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_gone_vanished", new BlockPos(8, 2, 8), false);
        Zombie zombie = still(helper, new BlockPos(11, 2, 8));
        zombie.setTarget(companion);

        steps(helper)
                .thenIdle(5)
                .thenExecute(() -> {
                    zombie.setNoGravity(true);
                    zombie.teleportTo(companion.getX() + 20.0, zombie.getY(), companion.getZ());
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertTrue(eventsOf(companion, com.dwinovo.numen.api.agent.inbox.EventTypes.GONE).isEmpty(),
                            "walking out of the radius was announced as gone: " + events(companion));
                    zombie.discard();
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    var gone = eventsOf(companion, com.dwinovo.numen.api.agent.inbox.EventTypes.GONE);
                    helper.assertTrue(gone.size() == 1 && gone.get(0).text().contains("reason=\"vanished\""),
                            "no vanished event: " + gone);
                    CompanionFactory.despawn(helper.getLevel().getServer(), companion);
                })
                .thenSucceed();
    }

    /** 她挨打的事件里,攻击者是短码、带方位。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
    public static void being_hit_names_the_attacker_by_its_id_and_says_which_way(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_hurt_label", new BlockPos(8, 2, 8), false);
        Zombie zombie = still(helper, new BlockPos(8, 2, 3));
        String id = com.dwinovo.numen.api.sdk.EntityLabel.of(zombie);

        steps(helper)
                .thenIdle(70)   // 刚进世界的玩家有一小段出生保护
                .thenExecute(() -> companion.hurt(zombie.damageSources().mobAttack(zombie), 3.0f))
                .thenIdle(5)
                .thenExecute(() -> {
                    var hurt = eventsOf(companion, com.dwinovo.numen.api.agent.inbox.EventTypes.COMPANION_HURT);
                    helper.assertTrue(hurt.size() == 1 && hurt.get(0).urgent()
                                    && hurt.get(0).text().contains("by=\"" + id + "\"")
                                    && hurt.get(0).text().contains("bearing=\"north\"")
                                    && hurt.get(0).text().contains(id + " hit you for 3"),
                            "the hurt event does not name the attacker and the way: " + hurt);
                    zombie.discard();
                    CompanionFactory.despawn(helper.getLevel().getServer(), companion);
                })
                .thenSucceed();
    }
}
