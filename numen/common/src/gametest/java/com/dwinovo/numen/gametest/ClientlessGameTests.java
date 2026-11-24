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

    /**
     * 原版 {@code /msg} 把话送到收话人手里的最后一步(它的 {@code MsgCommand.sendMessage} 是私有的,所以照它的写法重做):
     * 签了名的私聊带着发话人的 uuid,聊天类型是 {@code msg_command_incoming}。经指令的字符串走会被当成无来源的系统私聊,
     * 真客户端发的签名指令不会。
     */
    private static void whisper(ServerPlayer from, NumenPlayer to, String text) {
        to.sendChatMessage(OutgoingChatMessage.create(PlayerChatMessage.unsigned(from.getUUID(), text)), false,
                ChatType.bind(ChatType.MSG_COMMAND_INCOMING, from.createCommandSourceStack()));
    }

    /** 她的请求里写着眼前的主人游戏里选的语言:客户端报给服务端的那个值,给他翻消息用的也是它。 */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_clientless")
    public static void her_request_names_the_language_her_owners_game_is_set_to(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        ServerPlayer owner = GameTestKit.presentVanillaPlayer(helper, null, "gametest_zh_owner", "zh_cn");
        BrainGameTests.Brain her = new BrainGameTests.Brain(helper, "gametest_lang",
                Files.createTempDirectory("numen-gametest-brain-"), owner.getUUID(),
                Replies.link(server, owner.getUUID()));
        String state = her.loop.runtimeState();
        helper.assertTrue(state.contains("<owner language=\"Chinese (China)\"/>"),
                "her request does not name the language of her owner's game: " + state);
        her.close();
        GameTestKit.leave(owner);
        helper.succeed();
    }

    /** 主人私聊她:话进她的单聊会话,她回的话以私信的样子回到他手里。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_clientless")
    public static void the_owners_whisper_reaches_her_and_she_whispers_back(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        ServerPlayer owner = GameTestKit.presentVanillaPlayer(helper, null, "gametest_whisperer", "en_us");
        BrainGameTests.Brain her = new BrainGameTests.Brain(helper, "gametest_whispered",
                Files.createTempDirectory("numen-gametest-brain-"), owner.getUUID(),
                Replies.link(server, owner.getUUID()));
        her.mind.say("在呢");

        whisper(owner, her.companion, "你好");

        List<OwnerLine.Said> heard = new ArrayList<>();
        succeedWhen(helper, () -> {
            heard.addAll(GameTestKit.said(owner));
            helper.assertTrue(her.conversation().stream().anyMatch(m -> m instanceof
                            com.dwinovo.numen.api.agent.llm.ConvoState.Msg.User u && u.content().contains("你好")),
                    "the whisper is not in her log: " + her.conversation());
            helper.assertTrue(heard.stream().anyMatch(said -> said.text().getContents() instanceof TranslatableContents t
                            && t.getKey().equals("commands.message.display.incoming")
                            && said.text().getString().contains("在呢") && !said.overlay()),
                    "her answer did not come back as a whisper: " + heard);
            her.close();
            GameTestKit.leave(owner);
        });
    }

    /** 别的玩家私聊她:按旁听档进收件箱、标着是谁说的,不叫醒她。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_clientless")
    public static void a_strangers_whisper_is_overheard_and_does_not_wake_her(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        ServerPlayer owner = GameTestKit.presentVanillaPlayer(helper, null, "gametest_host", "en_us");
        ServerPlayer stranger = GameTestKit.presentVanillaPlayer(helper, null, "gametest_passerby", "en_us");
        BrainGameTests.Brain her = new BrainGameTests.Brain(helper, "gt_overhearer",
                Files.createTempDirectory("numen-gametest-brain-"), owner.getUUID(),
                Replies.link(server, owner.getUUID()));

        whisper(stranger, her.companion, "借把镐子");

        helper.assertTrue(her.loop.inbox().entries().stream().anyMatch(e -> e.type().equals(EventTypes.TALK)
                        && e.text().contains("gametest_passerby → gt_overhearer") && e.text().contains("借把镐子")),
                "the stranger's whisper is not in her inbox, marked with who said it: " + her.loop.inbox().entries());
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(her.mind.asked.isEmpty(), "a stranger's whisper woke her");
            her.close();
            GameTestKit.leave(owner);
            GameTestKit.leave(stranger);
            helper.succeed();
        });
    }

    /**
     * 创建者把她分享给一位朋友({@code /numen player share}):朋友在场她就在线(创建者走了也不登出),朋友的私聊和主人的话一样
     * 叫得到她、标着是谁说的;只有创建者能做的事(删除、清空记忆、再分享给别人)朋友做不了;朋友退出({@code /numen player leave})后
     * 她的主人表上无人在场,她登出。
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

        GameTestKit.steps(helper)
                .thenExecute(() -> {
                    com.dwinovo.numen.api.platform.PlayerSessions.left(creator);   // 平台的登出事件先到,玩家后移除
                    GameTestKit.leave(creator);
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    NumenPlayer still = NumenPlayer.findByUuid(server, id);
                    helper.assertTrue(still != null, "she logged out though the friend she is shared with is here");
                    whisper(friend, still, "你好");
                    var loop = com.dwinovo.numen.api.brain.CompanionLoops.get(id).orElseThrow();
                    String from = "from=\"player@" + friend.getUUID().toString().substring(0, 6) + " (gt_friend)\"";
                    helper.assertTrue(loop.inbox().entries().stream().anyMatch(e -> e.type().equals(EventTypes.QUERY)
                                    && e.text().contains("你好") && e.text().contains(from))
                                    || loop.log().loadLines(100).stream().anyMatch(line -> line.msg() instanceof
                                    com.dwinovo.numen.api.agent.llm.ConvoState.Msg.User u && u.content().contains("你好")
                                    && u.content().contains(from)),
                            "the friend's whisper did not reach her as an owner's words naming him: "
                                    + loop.inbox().entries());
                    helper.assertTrue(friend.getUUID().equals(loop.speaker()), "she is not answering the friend");

                    List<Component> refused = new ArrayList<>();
                    run(friend, "numen player delete gt_shared", refused);
                    helper.assertTrue(registry.find(id) != null
                                    && refused.stream().anyMatch(m -> isKey(m, Keys.COMMAND_NO_SUCH_COMPANION)),
                            "the friend could delete a companion he did not create: " + refused);
                    helper.assertTrue(com.dwinovo.numen.api.brain.OwnerInputs.apply(server, friend.getUUID(),
                                    new com.dwinovo.numen.api.brain.OwnerInput.Wipe(id)).problem() != null,
                            "the friend could wipe her memory");
                    helper.assertTrue(com.dwinovo.numen.api.brain.Sharing.share(server, friend.getUUID(), id,
                                    java.util.UUID.randomUUID()) == com.dwinovo.numen.api.brain.Sharing.Outcome.NOT_CREATOR,
                            "the friend could share her on");

                    run(friend, "numen player leave gt_shared", refused);
                    helper.assertTrue(!registry.find(id).usableBy(friend.getUUID()),
                            "leaving did not take him off her owners: " + refused);
                })
                .thenWaitUntil(() -> helper.assertTrue(NumenPlayer.findByUuid(server, id) == null,
                        "she stayed in the world with none of her owners present"))
                .thenExecute(() -> {
                    Companions.dismiss(server, creator.getUUID(), List.of(id));
                    GameTestKit.leave(friend);
                })
                .thenSucceed();
    }

    // ---- 征询与动作栏 ----

    /** 消息里依次出现的、点下去会敲的命令。 */
    private static List<String> clickCommands(Component message) {
        List<String> out = new ArrayList<>();
        if (message.getStyle().getClickEvent() instanceof net.minecraft.network.chat.ClickEvent click
                && click.getAction() == net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND) {
            out.add(click.getValue());
        }
        message.getSiblings().forEach(sibling -> out.addAll(clickCommands(sibling)));
        return out;
    }

    private static ConsentItem oakLog(GameTestHelper helper) {
        return new ConsentItem(Verb.BREAK, helper.absolutePos(new BlockPos(5, 2, 5)), ConsentItem.NO_ENTITY,
                "oak_log", Items.OAK_LOG, Blocks.OAK_LOG.getName(), "minecraft:oak_log", "belongs to the owner",
                Component.literal("it is yours"));
    }

    /**
     * 她的征询到了没装客户端的主人那里,是一条带三个可点按钮的聊天消息;点按钮就是他敲了 /numen consent,答复进同一个登记处。
     * 请求没等到答复就撤了,他在聊天栏里读到为什么。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_clientless")
    public static void a_consent_request_is_a_chat_message_with_buttons_for_an_owner_without_our_client(
            GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_asker", new BlockPos(3, 2, 3), false);
        ServerPlayer owner = GameTestKit.presentVanillaPlayer(helper, companion, "gametest_asked", "en_us");
        GameTestKit.said(owner);

        ConsentDesk.Ticket ticket = ConsentDesk.of(companion).ask(new Object(), List.of(oakLog(helper)));

        List<OwnerLine.Said> asked = GameTestKit.said(owner);
        long id = ticket.request().id();
        helper.assertTrue(asked.size() == 1 && asked.get(0).text().getString().contains("gametest_asker"),
                "the request did not reach him as one chat message: " + asked);
        helper.assertTrue(clickCommands(asked.get(0).text()).equals(List.of("/numen consent allow " + id,
                        "/numen consent remember " + id, "/numen consent deny " + id)),
                "the buttons are not allow, always allow and deny: " + clickCommands(asked.get(0).text()));

        owner.getServer().getCommands().performPrefixedCommand(owner.createCommandSourceStack(),
                clickCommands(asked.get(0).text()).get(0).substring(1));
        helper.assertTrue(ticket.poll() != null && ticket.poll().decision() == ConsentAnswer.Decision.ALLOW_ONCE,
                "pressing [allow] did not answer the request: " + ticket.poll());

        // 下一张没等到答复就撤了:他在聊天栏里读到是谁的请求、为什么撤
        Object scope = new Object();
        ConsentDesk.of(companion).ask(scope, List.of(oakLog(helper)));
        GameTestKit.said(owner);
        ConsentDesk.of(companion).release(scope, ConsentDesk.Withdrawal.TASK_ENDED);
        List<OwnerLine.Said> withdrawn = GameTestKit.said(owner);
        helper.assertTrue(withdrawn.size() == 1 && withdrawn.get(0).text().getString().contains("gametest_asker")
                        && withdrawn.get(0).text().getString().contains("went away"),
                "the withdrawal did not say whose request went away: " + withdrawn);

        CompanionFactory.despawn(owner.getServer(), companion);
        GameTestKit.leave(owner);
        helper.succeed();
    }

    /**
     * 没装客户端的主人在动作栏上看到她在干什么(他客户端的语言);装了客户端的主人不收动作栏——他的头顶气泡与档案页读同一份状态。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_clientless")
    public static void an_owner_without_our_client_sees_what_she_is_doing_on_the_action_bar(GameTestHelper helper)
            throws Exception {
        var server = helper.getLevel().getServer();
        ServerPlayer plain = GameTestKit.presentVanillaPlayer(helper, null, "gametest_watcher", "zh_cn");
        ServerPlayer installed = GameTestKit.presentPlayer(helper, null, "gametest_installed");
        Replies.hello(installed, com.dwinovo.numen.api.brain.Presence.PROTOCOL);
        BrainGameTests.Brain worker = new BrainGameTests.Brain(helper, "gametest_worker",
                Files.createTempDirectory("numen-gametest-brain-"), plain.getUUID(),
                Replies.link(server, plain.getUUID()));
        BrainGameTests.Brain other = new BrainGameTests.Brain(helper, "gametest_other",
                Files.createTempDirectory("numen-gametest-brain-"), installed.getUUID(),
                Replies.link(server, installed.getUUID()));
        worker.mind.program("gt.gt_sync.hold(100)").say("好了");
        other.mind.program("gt.gt_sync.hold(100)").say("好了");
        worker.loop.submitPrompt("干活");
        other.loop.submitPrompt("干活");

        List<OwnerLine.Said> bar = new ArrayList<>();
        List<OwnerLine.Said> installedBar = new ArrayList<>();
        succeedWhen(helper, () -> {
            GameTestKit.said(plain).stream().filter(OwnerLine.Said::overlay).forEach(bar::add);
            GameTestKit.said(installed).stream().filter(OwnerLine.Said::overlay).forEach(installedBar::add);
            helper.assertTrue(bar.stream().anyMatch(b -> b.text().getString().equals("gametest_worker: 执行")),
                    "what she is doing is not on his action bar in his language: " + bar);
            helper.assertTrue(installedBar.isEmpty(), "an owner with our client got an action bar: " + installedBar);
            worker.close();
            other.close();
            GameTestKit.leave(plain);
            GameTestKit.leave(installed);
        });
    }
}
