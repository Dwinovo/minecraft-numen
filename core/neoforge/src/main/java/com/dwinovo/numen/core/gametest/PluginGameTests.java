package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 插件接口:插件登记的身体状态片段与事件送到她那里。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class PluginGameTests {

    /** 插件批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_plugin")
    public static void preparePluginBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 插件经那扇门挂上的东西,和引擎自带的走同一条路:测试里登记一个假插件,它从身体上读一段状态
     * (只对这只同伴说话),再登记一种事件并发一条。{@code get_self_status} 里有那段状态;主人不在线,
     * 那条事件以插件登记的类型进出箱,kind 就是那个类型。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_plugin")
    public static void a_plugins_body_state_and_event_reach_her(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_charmed", new BlockPos(4, 2, 4), false);
        UUID self = companion.getUUID();
        com.dwinovo.numen.api.NumenPlugins.register(numen -> {
            numen.contributeBodyState(body -> body.getUUID().equals(self)
                    ? "<gametest_charm>wearing a gametest charm</gametest_charm>" : "");
            numen.registerEventType("gametest_charm_changed", false);
            numen.emit(companion, "gametest_charm_changed", java.util.Map.of("slot", "neck"),
                    "put on a gametest charm", false);
        });
        ToolRun reply = call(companion, "get_self_status", args());
        var outbox = com.dwinovo.numen.entity.EventOutbox.get(level.getServer());

        helper.succeedWhen(() -> {
            helper.assertTrue(reply.reply() != null, "get_self_status has not replied");
            var status = com.google.gson.JsonParser.parseString(reply.reply()).getAsJsonObject();
            // 身体状态片段以引擎渲染的 <worn> 打头,之后按登记顺序接:core 自己的 <throwaway>,最后登记的这个插件的片段
            helper.assertTrue(status.has("body_state") && status.get("body_state").getAsString().startsWith("<worn>")
                            && status.get("body_state").getAsString()
                            .endsWith("</throwaway><gametest_charm>wearing a gametest charm</gametest_charm>"),
                    "get_self_status leaves out what the plugin reads off her body: " + reply.reply());
            var kept = outbox.peek(self).entries().stream()
                    .filter(e -> e.type().equals("gametest_charm_changed")).toList();
            helper.assertTrue(kept.size() == 1
                            && kept.get(0).text().startsWith("<event kind=\"gametest_charm_changed\"")
                            && kept.get(0).text().contains("slot=\"neck\""),
                    "the plugin's event did not go out as its own kind: " + outbox.peek(self).entries());
            outbox.forget(self);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
