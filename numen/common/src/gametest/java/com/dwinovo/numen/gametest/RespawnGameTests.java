package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.agent.loop.Hold;
import com.dwinovo.numen.api.brain.CompanionLoops;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.gametest.OwnerLine;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * 死后重生:她死了,原版的重生流程把她放回世界(落点照原版规则,不经加入流程),还是同一个同伴,大脑接得上,
 * 死亡与重生都说给她听,之后照常干活。
 */
public class RespawnGameTests {

    /** 和平难度 + 正午:场景里除了用例自己动手,没有东西会伤她。 */
    @BeforeBatch(batch = "numen_respawn")
    public static void prepareBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 没睡过床:落在世界出生点。同一个同伴(UUID、主人不变,玩家列表里只有一份),大脑解冻;全服只听到死亡消息,没有"加入了游戏";
     * 收件箱里有死亡与重生两条事件,重生那条说的是世界出生点。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_respawn")
    public static void a_dead_companion_respawns_at_the_world_spawn_and_nobody_hears_her_join(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        NumenPlayer died = spawnAt(helper, "gametest_mortal", new BlockPos(4, 2, 4), false);
        UUID uuid = died.getUUID();
        UUID owner = died.getOwnerUuid();
        String name = died.getGameProfile().getName();
        ServerPlayer watcher = presentVanillaPlayer(helper, null, "gt_watcher", "en_us");
        said(watcher);   // 观察者自己加入时听到的不算

        died.kill();

        List<OwnerLine.Said> heard = new ArrayList<>();
        succeedWhen(helper, () -> {
            heard.addAll(said(watcher));
            NumenPlayer now = NumenPlayer.findByUuid(server, uuid);
            helper.assertTrue(now != null && now.isAlive(), "she has not respawned");
            helper.assertTrue(server.getPlayerList().getPlayers().stream().filter(p -> p.getUUID().equals(uuid)).count() == 1,
                    "she is in the player list more than once");
            helper.assertTrue(owner.equals(now.getOwnerUuid()), "the respawned body lost its owner");
            helper.assertTrue(CompanionLoops.get(uuid).orElseThrow().status().hold() != Hold.DEAD,
                    "her brain is still frozen as dead");

            BlockPos spawn = level.getSharedSpawnPos();
            helper.assertTrue(now.level().dimension() == Level.OVERWORLD
                            && Math.abs(now.getX() - spawn.getX()) <= 11 && Math.abs(now.getZ() - spawn.getZ()) <= 11,
                    "she did not land at the world spawn " + spawn + ": " + now.blockPosition());

            helper.assertTrue(heard.stream().anyMatch(s -> s.text().getString().contains(name)),
                    "nobody heard how she died: " + heard);
            helper.assertTrue(heard.stream().noneMatch(s -> s.text().getString().contains("joined the game")),
                    "the server announced her joining: " + heard);

            var events = events(now);
            helper.assertTrue(events.stream().anyMatch(e -> e.type().equals(EventTypes.DEATH)),
                    "she was not told she died: " + events);
            helper.assertTrue(events.stream().anyMatch(e -> e.type().equals(EventTypes.RESPAWNED)
                            && e.text().contains("世界出生点")),
                    "she was not told she landed at the world spawn: " + events);
            leave(watcher);
            CompanionFactory.despawn(server, now);
        });
    }

    /**
     * 睡过床:落在床边,重生事件说是重生点,并说主人在哪个方向、多远;她没有被传送到主人身边。
     * 重生后照常干活:走到几格外的一处。
     */
    @GameTest(template = "floor16", timeoutTicks = 600, batch = "numen_respawn")
    public static void a_companion_who_slept_in_a_bed_respawns_beside_it_and_walks_on(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos bed = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockState foot = Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT);
        level.setBlock(bed, foot, 3);
        level.setBlock(bed.east(), foot.setValue(BedBlock.PART, BedPart.HEAD), 3);
        NumenPlayer died = spawnAt(helper, "gametest_sleeper", new BlockPos(9, 2, 9), false);
        UUID uuid = died.getUUID();
        ServerPlayer owner = presentVanillaPlayer(helper, died, "gt_bedowner", "en_us");
        died.setRespawnPosition(level.dimension(), bed, 0.0f, false, false);

        died.kill();

        BlockPos goal = helper.absolutePos(new BlockPos(12, 2, 12));
        steps(helper)
                .thenWaitUntil(() -> {
                    NumenPlayer now = NumenPlayer.findByUuid(server, uuid);
                    helper.assertTrue(now != null && now.isAlive(), "she has not respawned");
                    helper.assertTrue(now.blockPosition().distSqr(bed) <= 3 * 3,
                            "she did not land beside her bed " + bed + ": " + now.blockPosition());
                    helper.assertTrue(owner.getUUID().equals(now.getOwnerUuid()), "the respawned body lost its owner");
                    var told = events(now).stream().filter(e -> e.type().equals(EventTypes.RESPAWNED)).toList();
                    helper.assertTrue(told.size() == 1 && told.get(0).text().contains("重生点")
                                    && told.get(0).text().contains("主人在你") && told.get(0).text().contains("格处"),
                            "she was not told where she landed and where her owner is: " + told);
                })
                .thenExecute(() -> lua(NumenPlayer.findByUuid(server, uuid), "numen.move.to(" + xyz(goal) + ")"))
                .thenWaitUntil(() -> helper.assertTrue(
                        NumenPlayer.findByUuid(server, uuid).blockPosition().distSqr(goal) <= 2 * 2,
                        "she has not walked on after respawning"))
                .thenExecute(() -> {
                    leave(owner);
                    CompanionFactory.despawn(server, NumenPlayer.findByUuid(server, uuid));
                })
                .thenSucceed();
    }
}
