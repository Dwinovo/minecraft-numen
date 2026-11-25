package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.brain.Replies;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import com.dwinovo.numen.permission.Verb;
import com.dwinovo.numen.api.permission.ConsentItem;
import com.dwinovo.numen.api.permission.ConsentDesk;
import com.dwinovo.numen.api.permission.ConsentAnswer;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.gametest.GameTestWorld;
import com.dwinovo.numen.api.gametest.OwnerLine;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.payload.ClientUiActionPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static com.dwinovo.numen.gametest.GameTestKit.spawnAt;
import static com.dwinovo.numen.gametest.GameTestKit.succeedWhen;

/**
 * 没装我们的客户端的主人(原版客户端):服务端不往他的连接上发我们的包,他能用的原版手段(私聊、指令、可点的聊天消息)见本类其余用例。
 * 玩家是 {@link GameTestKit#presentVanillaPlayer}:连接上没有我们的频道,下行的聊天记在连接上。
 */
public class ClientlessGameTests {

    // 每个批次都自己定下世界(难度、时刻、天气、模拟距离、权限设置),不继承上一批留下的:批次的先后说不准
    @BeforeBatch(batch = "numen_clientless")
    public static void prepareClientlessBatch(ServerLevel level) {
        GameTestKit.settleWorld(level, Difficulty.PEACEFUL, GameTestKit.NOON);
    }

    /** 这位玩家敲一条指令,指令回给他的话收进 {@code heard}(在送上线之前,还是带着语言键的 Component)。 */
    static void run(ServerPlayer player, String command, List<Component> heard) {
        CommandSourceStack source = player.createCommandSourceStack().withSource(GameTestWorld.hearing(heard::add));
        player.getServer().getCommands().performPrefixedCommand(source, command);
    }

    /** 这句话(指令的失败回话外面还包着一层红色的壳)里有没有这个语言键。 */
    static boolean isKey(Component message, String key) {
        return message.getContents() instanceof TranslatableContents t && t.getKey().equals(key)
                || message.getSiblings().stream().anyMatch(sibling -> isKey(sibling, key));
    }

    /** 可选频道:发给没有频道的连接,什么也不发、也不抛(NeoForge 在发送处就会抛);作用在他客户端上的指令说一声。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_clientless")
    public static void a_player_without_our_client_is_sent_none_of_our_packets(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerPlayer plain = GameTestKit.presentVanillaPlayer(helper, null, "gametest_plain", "en_us");

        Companions.syncRosterToOwner(server, plain);
        NumenNetwork.sendToPlayer(plain, new ClientUiActionPayload(ClientUiActionPayload.Action.DEBUG_TEXT_ON));
        helper.assertTrue(GameTestKit.received(plain).isEmpty(),
                "our packets were sent to a connection that has no channel for them");

        List<Component> heard = new ArrayList<>();
        run(plain, "numen settings", heard);
        helper.assertTrue(heard.size() == 1 && isKey(heard.get(0), Keys.COMMAND_NEEDS_CLIENT),
                "a command that acts on his client did not say he has none: " + heard);

        GameTestKit.leave(plain);
        helper.succeed();
    }

    /**
     * 没装客户端的玩家收到的是翻好的字:按他客户端报上来的语言,语言表读自 Numen 的 jar(客户端用的是同一份)。认不得的语言
     * 退回英文,和客户端自己的规矩一样。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_clientless")
    public static void a_player_without_our_client_is_told_in_the_language_his_client_reports(GameTestHelper helper) {
        ServerPlayer chinese = GameTestKit.presentVanillaPlayer(helper, null, "gametest_zh", "zh_cn");
        ServerPlayer english = GameTestKit.presentVanillaPlayer(helper, null, "gametest_en", "en_us");
        ServerPlayer elsewhere = GameTestKit.presentVanillaPlayer(helper, null, "gametest_xx", "xx_xx");
        List<ServerPlayer> players = List.of(chinese, english, elsewhere);
        for (ServerPlayer player : players) {
            GameTestKit.said(player);
            player.getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), "numen settings");
        }

        helper.assertTrue(GameTestKit.said(chinese).stream().anyMatch(s -> s.text().getString().contains("要装了 Numen 客户端")),
                "the Chinese client was not told in Chinese");
        for (ServerPlayer player : List.of(english, elsewhere)) {
            List<OwnerLine.Said> said = GameTestKit.said(player);
            helper.assertTrue(said.stream().anyMatch(s -> s.text().getString().contains("This needs the Numen client")),
                    "a client with no matching language was not told in English: " + said);
        }
        players.forEach(GameTestKit::leave);
        helper.succeed();
    }

    /** 座位给她的状态里写着主人游戏里选的语言:客户端报给服务端的那个值,给他翻消息用的也是它。 */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_clientless")
    public static void her_state_names_the_language_her_owners_game_is_set_to(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerPlayer owner = GameTestKit.presentVanillaPlayer(helper, null, "gametest_zh_owner", "zh_cn");
        NumenPlayer her = com.dwinovo.numen.api.gametest.Bodies.summon(server, owner.getUUID(), "gametest_lang",
                helper.getLevel(), net.minecraft.world.phys.Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(8, 2, 8))));
        com.dwinovo.numen.api.gametest.GameBrain brain = com.dwinovo.numen.api.gametest.GameBrain.sit(server, her,
                new BrainGameTests.Scripted());
        java.util.concurrent.CompletableFuture<?>[] asked = new java.util.concurrent.CompletableFuture<?>[1];
        succeedWhen(helper, () -> {
            helper.assertTrue(brain.ready(), "her brain has not taken the seat");
            if (asked[0] == null) {
                asked[0] = brain.state();
            }
            helper.assertTrue(asked[0].isDone(), "the seat has not answered the state request");
            String state = ((com.dwinovo.numen.api.seat.SeatClient.Sitting) asked[0].join()).state().text();
            helper.assertTrue(state.contains("<owner language=\"Chinese (China)\"/>"),
                    "her state does not name the language of her owner's game: " + state);
            brain.close();
            Companions.dismiss(server, owner.getUUID(), List.of(her.getUUID()));
            GameTestKit.leave(owner);
        });
    }

    /**
     * 创建者把她分享给一位朋友({@code /numen player share}):朋友进了她的主人表;只有创建者能做的事(删除、再分享给别人)朋友做不了;
     * 她的大脑只在创建者的客户端上,创建者走了,没有谁能驾驶她,她就登出——朋友在场也一样;朋友退出({@code /numen player leave})后
     * 她的主人表上只剩创建者。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_clientless")
    public static void a_shared_companion_serves_the_friend_and_stays_her_creators(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        ServerPlayer creator = GameTestKit.presentVanillaPlayer(helper, null, "gt_sharer", "en_us");
        ServerPlayer friend = GameTestKit.presentVanillaPlayer(helper, null, "gt_friend", "en_us");
        NumenPlayer her = com.dwinovo.numen.api.gametest.Bodies.summon(server, creator.getUUID(), "gt_shared", level,
                net.minecraft.world.phys.Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(8, 2, 8))));
        java.util.UUID id = her.getUUID();
        var registry = com.dwinovo.numen.api.entity.CompanionRegistry.get(server);
        List<Component> heard = new ArrayList<>();

        run(creator, "numen player share gt_shared gt_friend", heard);
        helper.assertTrue(registry.find(id).usableBy(friend.getUUID()),
                "the share command did not put him on her owners: " + heard);

        List<Component> refused = new ArrayList<>();
        run(friend, "numen player delete gt_shared", refused);
        helper.assertTrue(registry.find(id) != null
                        && refused.stream().anyMatch(m -> isKey(m, Keys.COMMAND_NO_SUCH_COMPANION)),
                "the friend could delete a companion he did not create: " + refused);
        helper.assertTrue(com.dwinovo.numen.api.brain.Sharing.share(server, friend.getUUID(), id,
                        java.util.UUID.randomUUID()) == com.dwinovo.numen.api.brain.Sharing.Outcome.NOT_CREATOR,
                "the friend could share her on");

        run(friend, "numen player leave gt_shared", refused);
        helper.assertTrue(!registry.find(id).usableBy(friend.getUUID()),
                "leaving did not take him off her owners: " + refused);

        GameTestKit.steps(helper)
                .thenExecute(() -> {
                    com.dwinovo.numen.api.platform.PlayerSessions.left(creator);   // 平台的登出事件先到,玩家后移除
                    GameTestKit.leave(creator);
                })
                .thenWaitUntil(() -> helper.assertTrue(NumenPlayer.findByUuid(server, id) == null,
                        "she stayed in the world though her creator's seat is empty"))
                .thenExecute(() -> {
                    Companions.dismiss(server, creator.getUUID(), List.of(id));
                    GameTestKit.leave(friend);
                })
                .thenSucceed();
    }
}
