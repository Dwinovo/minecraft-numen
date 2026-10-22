package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.Controls;
import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;

import java.util.List;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * YSM 动作的收尾:她放一个动作之后,动了就停、换模型就停、站着不动不停。都从 Lua 入口调({@code ysm.model.emote} 与
 * {@code ysm.model.switch}、{@code numen.move.to}),断言看联动对 YSM 发出的命令(见 {@link YsmStub}),不读 YSM 的内部状态。
 *
 * <p>只在本模块那一次跑批里跑({@code :plugins:ysm:gametest:runGameTestServer}),套件 {@code numen_ysm}(见 {@link YsmGameTestSuite})。
 */
public class YsmGameTests {

    private static final String BATCH = "numen_ysm";

    @BeforeBatch(batch = BATCH)
    public static void prepareBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
        YsmStub.install(level.getServer());
    }

    /** 放了动作、她走起来:停一次;走回来再走,不再重复停。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void walking_stops_the_emote_once(GameTestHelper helper) {
        String label = "gametest_ysm_walker";
        NumenPlayer her = spawnAt(helper, label, new BlockPos(3, 2, 3), false);
        String name = her.getName().getString();
        BlockPos far = helper.absolutePos(new BlockPos(10, 2, 3));
        BlockPos near = helper.absolutePos(new BlockPos(3, 2, 3));
        ToolRun emote = lua(her, "ysm.model.emote(\"extra1\")");
        ToolRun[] walks = new ToolRun[2];

        steps(helper)
                .thenExecute(() -> helper.assertTrue(emote.succeeded() && YsmStub.sentTo(name)
                        .equals(List.of("play " + name + " extra1")), "emote went wrong: " + emote.reply()
                        + " / " + YsmStub.sentTo(name)))
                .thenIdle(10)
                .thenExecute(() -> helper.assertTrue(YsmStub.sentTo(name).size() == 1,
                        "she stopped while standing still: " + YsmStub.sentTo(name)))
                .thenExecute(() -> walks[0] = lua(her, "numen.move.to(" + xyz(far) + ")"))
                .thenWaitUntil(() -> helper.assertTrue(YsmStub.sentTo(name).contains("play " + name + " stop"),
                        "walking did not stop the emote: " + YsmStub.sentTo(name)))
                .thenWaitUntil(() -> helper.assertTrue(walks[0].done(), "walk has not finished: " + walks[0].reply()))
                .thenExecute(() -> walks[1] = lua(her, "numen.move.to(" + xyz(near) + ")"))
                .thenWaitUntil(() -> helper.assertTrue(walks[1].done(), "walk back has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(YsmStub.sentTo(name).equals(
                                    List.of("play " + name + " extra1", "play " + name + " stop")),
                            "the stop was not sent exactly once: " + YsmStub.sentTo(name));
                    despawn(helper, her);
                })
                .thenSucceed();
    }

    /** 蹲下也算动:按着潜行键,动作停了。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void sneaking_stops_the_emote(GameTestHelper helper) {
        String label = "gametest_ysm_sneaker";
        NumenPlayer her = spawnAt(helper, label, new BlockPos(3, 2, 3), false);
        String name = her.getName().getString();
        lua(her, "ysm.model.emote(\"extra1\")");

        steps(helper)
                .thenExecute(() -> her.controls().press(Controls.Key.SNEAK))
                .thenWaitUntil(() -> helper.assertTrue(YsmStub.sentTo(name).contains("play " + name + " stop"),
                        "sneaking did not stop the emote: " + YsmStub.sentTo(name)))
                .thenExecute(() -> despawn(helper, her))
                .thenSucceed();
    }

    /** 站着不动,动作不被收走。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void standing_still_keeps_the_emote(GameTestHelper helper) {
        String label = "gametest_ysm_stander";
        NumenPlayer her = spawnAt(helper, label, new BlockPos(3, 2, 3), false);
        String name = her.getName().getString();
        lua(her, "ysm.model.emote(\"extra1\")");

        steps(helper)
                .thenIdle(60)
                .thenExecute(() -> {
                    helper.assertTrue(YsmStub.sentTo(name).equals(List.of("play " + name + " extra1")),
                            "she was stopped without moving: " + YsmStub.sentTo(name));
                    despawn(helper, her);
                })
                .thenSucceed();
    }

    /** 放着动作换模型:换好的同时动作停了,回执里说了。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void switching_model_stops_the_emote_and_says_so(GameTestHelper helper) {
        String label = "gametest_ysm_switcher";
        NumenPlayer her = spawnAt(helper, label, new BlockPos(3, 2, 3), false);
        String name = her.getName().getString();
        lua(her, "ysm.model.emote(\"extra1\")");
        ToolRun switched = lua(her, "ysm.model.switch(\"misc/1_alex\", {texture = \"alex\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(switched.done() && switched.succeeded(), "switch did not finish: " + switched.reply());
            helper.assertTrue(switched.reply().contains("原来的动作停了"),
                    "the receipt does not say the emote stopped: " + switched.reply());
            helper.assertTrue(YsmStub.sentTo(name).equals(List.of("play " + name + " extra1",
                            "model " + name + " misc/1_alex alex", "play " + name + " stop")),
                    "unexpected commands: " + YsmStub.sentTo(name));
            despawn(helper, her);
        });
    }

    /** 没有动作在放时换模型:不多发 stop,回执也不提动作。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void switching_model_without_an_emote_sends_no_stop(GameTestHelper helper) {
        String label = "gametest_ysm_plain";
        NumenPlayer her = spawnAt(helper, label, new BlockPos(3, 2, 3), false);
        String name = her.getName().getString();
        ToolRun switched = lua(her, "ysm.model.switch(\"misc/1_alex\", {texture = \"alex\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(switched.done() && switched.succeeded(), "switch did not finish: " + switched.reply());
            helper.assertTrue(!switched.reply().contains("动作"), "the receipt mentions an emote: " + switched.reply());
            helper.assertTrue(YsmStub.sentTo(name).equals(List.of("model " + name + " misc/1_alex alex")),
                    "unexpected commands: " + YsmStub.sentTo(name));
            despawn(helper, her);
        });
    }

    private static void despawn(GameTestHelper helper, NumenPlayer her) {
        com.dwinovo.numen.api.entity.CompanionFactory.despawn(helper.getLevel().getServer(), her);
    }
}
